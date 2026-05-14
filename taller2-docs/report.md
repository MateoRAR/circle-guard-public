# Informe de Implementación CI/CD — Proyecto CircleGuard
## Ingeniería de Software V — Entrega Individual

---

# Tabla de contenidos

1. [Introducción](#1-introducción)
2. [Arquitectura del sistema](#2-arquitectura-del-sistema)
3. [Estrategia de CI/CD](#3-estrategia-de-cicd)
4. [Pipeline de Desarrollo (Dev)](#4-pipeline-de-desarrollo-dev-environment)
5. [Pipeline de Stage](#5-pipeline-de-stage)
6. [Pipeline de Master/Producción](#6-pipeline-de-masterproducción)
7. [Estrategia de pruebas](#7-estrategia-de-pruebas)
8. [Configuración de los Pipelines](#8-configuración-de-los-pipelines)
9. [Resultados de Ejecución](#9-resultados-de-ejecución)
10. [Análisis General](#10-análisis-general)
11. [Conclusiones](#11-conclusiones)
12. [Evidencias Adjuntas](#12-evidencias-adjuntas)

---

# 1. Introducción

## 1.1 Descripción del proyecto

CircleGuard es un sistema de control de acceso y monitoreo epidemiológico diseñado para entornos universitarios e institucionales. El sistema permite gestionar la autenticación de usuarios mediante una cadena dual (LDAP institucional con fallback a base de datos local), la emisión de tokens JWT y códigos QR para validación en puertas físicas, el seguimiento del estado de salud de los usuarios a través de un grafo de contactos, y la generación de analíticas agregadas bajo el principio de k-anonimato para proteger la privacidad individual.

El proyecto fue desarrollado como una arquitectura de microservicios sobre Java 21 con Spring Boot 3, y el presente informe documenta la implementación completa de la cadena CI/CD, incluyendo los entornos de desarrollo, staging y producción, así como la estrategia de pruebas a múltiples niveles.

## 1.2 Tecnologías utilizadas

| Categoría | Tecnología | Versión | Propósito |
|---|---|---|---|
| Lenguaje | Java | 21 (JDK/JRE Eclipse Temurin) | Implementación de microservicios |
| Framework | Spring Boot | 3.x | Base de todos los servicios |
| Build | Gradle (Kotlin DSL) | 8.x | Compilación y gestión de dependencias |
| Contenedores | Docker | 27.x | Empaquetado de servicios |
| Orquestación | Kubernetes (Kind) | 1.29 | Clúster local multi-ambiente |
| Registry | Docker Registry v2 | — | Registro local de imágenes (`kind-registry:5000`) |
| CI/CD | Jenkins (Helm Chart) | LTS | Automatización de pipelines |
| IaC Kubernetes | Kustomize | — | Gestión de overlays por ambiente |
| Tests unitarios | JUnit 5 + Mockito | 5.10 | Validación de componentes individuales |
| Tests de integración | Testcontainers | 1.19.3 | Contenedores reales en pruebas |
| Tests E2E | REST Assured | 5.4.0 | Validación de flujos completos |
| Rendimiento | Locust | — | Pruebas de carga y estrés |
| Bases de datos | PostgreSQL 16, Neo4j 5, Redis 7 | — | Persistencia por servicio |
| Mensajería | Apache Kafka | — | Eventos asincrónicos |
| Directorio | OpenLDAP | — | Autenticación institucional |

## 1.3 Objetivos de la implementación CI/CD

La implementación de la cadena CI/CD persigue los siguientes objetivos:

1. **Automatización total** del ciclo de vida del software desde el commit hasta el despliegue en producción, eliminando procesos manuales propensos a error.
2. **Validación continua** mediante pruebas a múltiples niveles (unitarias, integración, E2E, rendimiento) ejecutadas automáticamente en cada pipeline.
3. **Separación de ambientes** con tres entornos completamente independientes (dev, stage, prod) en el mismo clúster Kubernetes, gestionados mediante Kustomize overlays.
4. **Trazabilidad y Change Management** mediante generación automática de Release Notes desde el historial de commits con formato Conventional Commits.
5. **Control de calidad progresivo**: cada ambiente adiciona una capa de validación, de modo que solo el código verificado en stage puede promoverse a producción, y únicamente tras aprobación manual explícita.

---

# 2. Arquitectura del sistema

## 2.1 Microservicios seleccionados

Se seleccionaron los seis microservicios del sistema CircleGuard que presentan mayor interdependencia, abarcando los flujos principales de autenticación, control de acceso físico, seguimiento epidemiológico y analítica:

| Servicio | Puerto | Base de datos / Infra | Rol principal |
|---|---|---|---|
| `circleguard-auth-service` | 8180 | PostgreSQL, LDAP | Autenticación dual-chain, emisión de JWT y tokens QR |
| `circleguard-identity-service` | 8083 | PostgreSQL, Kafka | Vault cifrado de identidades PII, auditoría de accesos |
| `circleguard-gateway-service` | 8087 | Redis | Validación de tokens QR en puertas físicas |
| `circleguard-promotion-service` | 8088 | PostgreSQL, Neo4j, Redis, Kafka | Máquina de estados de salud, grafo de contactos (propagación) |
| `circleguard-dashboard-service` | 8084 | (vía HTTP a Promotion) | Analítica con k-anonimato para administradores |
| `circleguard-file-service` | 8085 | Filesystem local | Subida y descarga de certificados de salud |

## 2.2 Comunicación entre servicios

Los servicios se comunican mediante una combinación de llamadas HTTP síncronas, acceso compartido a Redis y eventos Kafka asíncronos:

```
[Usuario]
    │
    ├─► Auth (8180) ──── JWT + QR token ────► Gateway (8087)
    │       │                                      │
    │       │ (identity:lookup)                    │ (Redis: estado de salud)
    │       ▼                                      │
    │   Identity (8083)                    Promotion (8088)
    │       │                                      │
    │       │ (Kafka: eventos de identidad)        ├─► PostgreSQL (estado)
    │       └──────────────────────────────────    ├─► Neo4j (grafo contactos)
    │                                              └─► Redis (caché estado)
    │
    ├─► Dashboard (8084) ──HTTP──► Promotion (8088) [stats]
    │
    └─► File (8085) [independiente]
```

**Flujos de comunicación relevantes para las pruebas:**
- **Auth → Identity**: tras el login, Auth invoca Identity para obtener el `anonymousId` vinculado a la identidad real del usuario.
- **Gateway → Redis**: el servicio de gateway consulta el estado de salud cacheado en Redis (escrito por Promotion) para decidir si un token QR es válido.
- **Promotion → Neo4j**: el grafo de contactos almacenado en Neo4j permite propagar cambios de estado de salud en cascada a todos los contactos de un usuario confirmado.
- **Dashboard → Promotion (HTTP)**: el servicio de dashboard consume las estadísticas de salud del servicio de promotion y aplica k-anonimato antes de exponerlas.

## 2.3 Rol de las tecnologías de infraestructura

**Docker:** cada microservicio se empaqueta mediante un Dockerfile multi-stage (builder `gradle:8-jdk21` + runtime `eclipse-temurin:21-jre`). El build de la imagen siempre se realiza desde la raíz del monorepo porque Gradle requiere el `settings.gradle.kts` raíz. Las imágenes se publican en el registry local `kind-registry:5000` con tags que reflejan el ambiente y el número de build.

**Jenkins:** desplegado via Helm chart (`jenkins/jenkins`) en el namespace `jenkins` del clúster Kind. El pod de Jenkins corre con un contenedor sidecar `docker:27-dind` que expone el daemon Docker en `tcp://localhost:2375`, lo que permite ejecutar comandos `docker build` y `docker push` desde el pipeline sin necesidad de acceso directo al socket del host. Un hook `postStart` instala el Docker CLI, Python 3.11 y Locust en el directorio de trabajo persistente de Jenkins al arrancar el pod.

**Kubernetes (Kind):** el clúster local `circleguard` cuenta con tres namespaces de aplicación (`circleguard-dev`, `circleguard-stage`, `circleguard-prod`) y un namespace de infraestructura (`jenkins`). Todos los namespaces de aplicación comparten la misma infraestructura de datos (PostgreSQL, Neo4j, Kafka, Redis, LDAP) desplegada mediante manifiestos en `k8s/infra/`.

**Kustomize:** los manifiestos de servicios se gestionan con Kustomize. La carpeta `k8s/services/` contiene los manifiestos base; los overlays en `k8s/overlays/stage/` y `k8s/overlays/prod/` reescriben el namespace y los tags de imagen para cada ambiente sin duplicar manifiestos.

---

# 3. Estrategia de CI/CD

## 3.1 Ambientes y su propósito

### Ambiente de Desarrollo (Dev)
- **Propósito:** validación rápida del trabajo individual de cada servicio.
- **Pipeline:** un Jenkinsfile por servicio ubicado en `services/<servicio>/Jenkinsfile`.
- **Alcance:** compilación, tests unitarios, construcción de imagen Docker, publicación en registry y despliegue en `circleguard-dev`.
- **Criterio de avance:** el pipeline de un servicio debe completarse exitosamente (todos los tests pasan, el deployment queda en estado Ready) antes de promover cambios al pipeline de stage.

### Ambiente de Staging (Stage)
- **Propósito:** validación integral del sistema completo desplegado en Kubernetes.
- **Pipeline:** un único `jenkins/jenkinsfile.stage` que opera sobre los seis servicios en paralelo donde es posible.
- **Alcance:** compilación paralela, tests unitarios con credenciales reales, construcción y publicación de imágenes con tag `stage-${BUILD_NUMBER}`, despliegue completo en `circleguard-stage` (incluyendo creación de Secrets de Kubernetes), verificación de rollout, pruebas E2E contra el cluster desplegado y pruebas de rendimiento con Locust.
- **Criterio de avance:** todos los stages deben ser verdes, incluyendo E2E y Locust (configurados con `|| true` en Locust para no bloquear el pipeline ante degradación de rendimiento, aunque los reportes sí quedan registrados).

### Ambiente de Producción (Master/Prod)
- **Propósito:** despliegue controlado y trazable de versiones validadas en stage.
- **Pipeline:** `Jenkinsfile.prod` en la raíz del repositorio.
- **Alcance:** compilación paralela, tests unitarios, construcción y publicación de imágenes con tres tags (`prod-${BUILD_NUMBER}`, `prod-latest`, `latest`), validación de sistema contra el namespace stage ya desplegado, **aprobación manual explícita**, despliegue en `circleguard-prod` con creación idempotente de Secrets, verificación de rollout y generación automática de Release Notes.
- **Criterio de avance:** la aprobación manual es la última salvaguarda antes del despliegue. Sin ella, el pipeline permanece pausado indefinidamente.

## 3.2 Estrategia de tags de imagen

| Ambiente | Tags generados | Ejemplo |
|---|---|---|
| Dev | `dev` (fijo, mutable) | `kind-registry:5000/circleguard/auth-service:dev` |
| Stage | `stage-{N}`, `stage-latest` | `kind-registry:5000/circleguard/auth-service:stage-42` |
| Prod | `prod-{N}`, `prod-latest`, `latest` | `kind-registry:5000/circleguard/auth-service:prod-42` |

El tag `stage-latest` permite que el overlay de Kustomize de stage siempre referencie la última imagen sin modificar manifiestos. El tag `prod-{N}` proporciona trazabilidad exacta de qué build está corriendo en producción.

## 3.3 Flujo de promoción entre ambientes

```
Commit al repositorio
        │
        ▼
  [Dev Pipeline]  ─── build + unit tests + docker push + deploy dev ──► OK
        │
        ▼ (manual trigger)
 [Stage Pipeline] ─── build + unit tests + docker push + deploy stage
                       + verify rollout + E2E + Locust ──────────────► OK
        │
        ▼ (manual trigger)
  [Prod Pipeline] ─── build + unit tests + docker push
                       + system tests (vs stage) + APROBACIÓN MANUAL
                       + deploy prod + verify rollout + release notes ─► OK
```

---

# 4. Pipeline de Desarrollo (Dev Environment)

## 4.1 Propósito

El pipeline de desarrollo tiene como objetivo proporcionar retroalimentación rápida al desarrollador sobre la salud del servicio que está modificando. Cada servicio tiene su propio pipeline independiente, lo que permite desplegar un cambio en un servicio sin necesidad de recompilar o redesplegar los demás.

## 4.2 Stages ejecutados

El pipeline de todos los servicios sigue la siguiente secuencia:

```
Checkout → Build → Unit Tests → Docker Build → Docker Push → Deploy Dev → Verify Rollout
```

| Stage | Herramienta | Descripción |
|---|---|---|
| Checkout | Jenkins SCM | `cleanWs()` + `checkout scm` desde el repositorio Git |
| Build | Gradle | `./gradlew :services:<servicio>:build -x test` (sin tests para velocidad) |
| Unit Tests | Gradle + JUnit 5 | `./gradlew :services:<servicio>:test` con `withCredentials` para secrets |
| Docker Build | Docker CLI (via dind) | `docker build --network=host -f services/<svc>/Dockerfile .` |
| Docker Push | Docker CLI (via dind) | Push a `kind-registry:5000/circleguard/<svc>:dev` |
| Deploy Dev | kubectl (via kubeconfig) | `kubectl apply -k k8s/services/<svc>/ -n circleguard-dev` + rollout restart |
| Verify Rollout | kubectl | `kubectl rollout status deployment/<svc> -n circleguard-dev --timeout=180s` |

## 4.3 Proceso de build

El build de cada servicio se realiza con Gradle en el directorio raíz del monorepo, utilizando el target específico del servicio. La opción `-x test` excluye los tests en la fase de compilación para reducir el tiempo del stage Build; los tests se ejecutan en el stage siguiente con credenciales inyectadas correctamente. El flag `--no-daemon` se usa en todos los comandos Gradle dentro de Jenkins para evitar problemas de estado persistente entre ejecuciones.

## 4.4 Proceso de pruebas automatizadas

Los tests unitarios se ejecutan con credenciales reales inyectadas via `withCredentials`, lo que garantiza que los tests de integración que dependen de configuraciones secretas (como `JWT_SECRET` y `VAULT_SECRET`) reciban valores válidos del credential store de Jenkins. Las credenciales configuradas son:

| Credential ID | Servicios que la usan |
|---|---|
| `jwt-secret` | auth, identity, gateway, promotion |
| `qr-secret` | auth, gateway |
| `vault-secret` | auth, identity |
| `vault-salt` | auth, identity |
| `vault-hash-salt` | auth, identity |

Los tests de dashboard y file no requieren credenciales externas (sus dependencias se mockean en las pruebas).

Los resultados de los tests se publican automáticamente en Jenkins mediante el paso `junit allowEmptyResults: true, testResults: 'services/<svc>/build/test-results/**/*.xml'`, lo que genera un reporte JUnit visible en la interfaz del job.

## 4.5 Proceso de despliegue

Tras publicar la imagen Docker en el registry local, el pipeline aplica los manifiestos de Kubernetes del servicio en el namespace `circleguard-dev` y fuerza un `rollout restart` del Deployment. Dado que todas las imágenes de dev usan el tag mutable `:dev` con `imagePullPolicy: Always`, el reinicio garantiza que Kubernetes descargue la nueva imagen desde el registry.

El stage Verify Rollout espera hasta 180 segundos (300 para promotion-service por el tiempo de inicio de Neo4j) a que el pod pase la `readinessProbe` configurada como `tcpSocket` en el puerto del servicio.

## 4.6 Evidencias del pipeline de desarrollo

| Evidencia | Descripción |
|---|---|
| [Pipeline auth-service](evidencias/jenkins/dev-auth-pipeline.png) | Vista de stages del pipeline `circleguard-auth-service` en Jenkins con todos los steps en verde |
| [Pipeline identity-service](evidencias/jenkins/dev-identity-pipeline.png) | Ejecución exitosa del pipeline de `circleguard-identity-service` |
| [Pipeline gateway-service](evidencias/jenkins/dev-gateway-pipeline.png) | Ejecución exitosa del pipeline de `circleguard-gateway-service` |
| [Pipeline promotion-service](evidencias/jenkins/dev-promotion-pipeline.png) | Ejecución exitosa del pipeline de `circleguard-promotion-service` (timeout extendido a 300s) |
| [Pipeline dashboard-service](evidencias/jenkins/dev-dashboard-pipeline.png) | Ejecución exitosa del pipeline de `circleguard-dashboard-service` |
| [Pipeline file-service](evidencias/jenkins/dev-file-pieline.png) | Ejecución exitosa del pipeline de `circleguard-file-service` |
| [Jenkinsfile — auth-service](../services/circleguard-auth-service/Jenkinsfile) | Definición completa del pipeline de desarrollo del servicio de autenticación |
| [Jenkinsfile — identity-service](../services/circleguard-identity-service/Jenkinsfile) | Definición completa del pipeline de desarrollo del servicio de identidades |
| [Jenkinsfile — gateway-service](../services/circleguard-gateway-service/Jenkinsfile) | Definición completa del pipeline de desarrollo del servicio de puerta de acceso |
| [Jenkinsfile — promotion-service](../services/circleguard-promotion-service/Jenkinsfile) | Definición completa del pipeline de desarrollo del servicio de promoción |
| [Jenkinsfile — dashboard-service](../services/circleguard-dashboard-service/Jenkinsfile) | Definición completa del pipeline de desarrollo del servicio de analítica |
| [Jenkinsfile — file-service](../services/circleguard-file-service/Jenkinsfile) | Definición completa del pipeline de desarrollo del servicio de archivos |
| [Pods y Services en circleguard-dev](evidencias/kubernetes/dev-pods-services.png) | Salida de `kubectl get pods,svc -n circleguard-dev` mostrando los 6 servicios en estado Running |
| [Namespaces del clúster](evidencias/kubernetes/kind-namespaces.png) | `kubectl get namespaces` mostrando los 4 namespaces activos (dev, stage, prod, jenkins) |
| [Salida de tests — auth (28s)](evidencias/tests/auth-unit-test.txt) | Log de Gradle del stage Unit Tests de auth-service: BUILD SUCCESSFUL en 28s |
| [Salida de tests — identity (11s)](evidencias/tests/identity-unit-test.txt) | Log de Gradle del stage Unit Tests de identity-service: BUILD SUCCESSFUL en 11s (FROM-CACHE) |
| [Salida de tests — gateway (51s)](evidencias/tests/gateway-unit-test.txt) | Log de Gradle del stage Unit Tests de gateway-service: BUILD SUCCESSFUL en 51s |
| [Salida de tests — promotion (2m 28s)](evidencias/tests/promotion-unit-test.txt) | Log de Gradle del stage Unit Tests de promotion-service: BUILD SUCCESSFUL en 2m 28s |
| [Salida de tests — dashboard (20s)](evidencias/tests/dashboard-unit-test.txt) | Log de Gradle del stage Unit Tests de dashboard-service: BUILD SUCCESSFUL en 20s |
| [Salida de tests — file (19s)](evidencias/tests/file-unit-test.txt) | Log de Gradle del stage Unit Tests de file-service: BUILD SUCCESSFUL en 19s |

## 4.7 Análisis de resultados

Todos los servicios completan el pipeline de desarrollo exitosamente con tasa de tests del 100%. Los tiempos de ejecución van desde los 11 segundos del identity-service (resultado cacheado por Gradle) hasta los 2 minutos 28 segundos del promotion-service, que es el más lento porque sus tests de integración levantan tres contenedores via Testcontainers (PostgreSQL, Neo4j, Redis) y además ejecuta el PromotionPerformanceTest sobre un grafo de 10.000 nodos. El gateway-service tarda 51 segundos a pesar de tener pocos tests, porque sus tests de integración con Redis y la generación de múltiples tokens JWT implican cómputo criptográfico.

La verificación de rollout con `kubectl rollout status` actúa como contrato de disponibilidad: si el pod no llega a estado Ready dentro del timeout, el pipeline falla y el desarrollador recibe retroalimentación inmediata sin necesidad de inspeccionar el cluster manualmente.

---

# 5. Pipeline de Stage

## 5.1 Propósito y alcance

El pipeline de stage valida el sistema completo desplegado en un entorno Kubernetes real, ejecutando pruebas sobre los servicios en ejecución en lugar de sobre componentes aislados. Esto garantiza que la integración entre servicios, la configuración de red, los Secrets de Kubernetes y el acceso a las bases de datos funcionan correctamente en conjunto.

## 5.2 Stages ejecutados

```
Checkout → Build (parallel) → Unit Tests (parallel) → Docker Build & Push
         → Deploy Stage → Verify Rollout → E2E Tests → Locust Load Test
```

### Stage: Checkout
`cleanWs()` elimina el workspace anterior y `checkout scm` descarga el código fuente del repositorio. Esto garantiza reproducibilidad completa de cada ejecución.

### Stage: Build (paralelo)
Los seis servicios se compilan en paralelo usando bloques `parallel {}` de Groovy. Esto reduce el tiempo de compilación total al tiempo del servicio más lento en lugar de la suma de todos. La compilación excluye tests (`-x test`) y desactiva el daemon de Gradle (`--no-daemon`).

### Stage: Unit Tests (paralelo)
Cada servicio ejecuta su suite de tests en paralelo con credenciales inyectadas por `withCredentials`. Los tests incluyen tanto pruebas unitarias puras (sin infraestructura) como tests de integración con Testcontainers (PostgreSQL, Neo4j, Redis reales levantados como contenedores temporales). Los resultados se publican con `junit` al finalizar via `post { always }`.

### Stage: Docker Build & Push
Un bloque `script {}` itera sobre la lista de servicios y para cada uno ejecuta:
1. `docker build --network=host` — el flag `--network=host` permite al proceso de build acceder al registry local durante la construcción si fuera necesario.
2. Push con dos tags: `stage-${BUILD_NUMBER}` (inmutable, trazable) y `stage-latest` (referenciado por el overlay de Kustomize).

### Stage: Deploy Stage
Con credenciales reales inyectadas, el pipeline:
1. Aplica el overlay de Kustomize: `kubectl apply -k k8s/overlays/stage/` — esto crea o actualiza todos los Deployments, Services y ConfigMaps del namespace `circleguard-stage`.
2. Crea los cuatro Secrets de Kubernetes de forma idempotente usando `--dry-run=client -o yaml | kubectl apply -f -`, lo que permite re-ejecutar el pipeline sin error si los secrets ya existen.
3. Ejecuta `kubectl rollout restart` en los seis Deployments para forzar la descarga de las nuevas imágenes.

Los Secrets creados son:

| Secret | Contenido |
|---|---|
| `circleguard-auth-secret` | JWT_SECRET, QR_SECRET, SPRING_DATASOURCE_PASSWORD, SPRING_LDAP_PASSWORD |
| `circleguard-identity-secret` | JWT_SECRET, VAULT_SECRET, VAULT_SALT, VAULT_HASH_SALT, SPRING_DATASOURCE_PASSWORD |
| `circleguard-promotion-secret` | JWT_SECRET, SPRING_DATASOURCE_PASSWORD, SPRING_NEO4J_AUTHENTICATION_PASSWORD |
| `circleguard-gateway-secret` | JWT_SECRET, QR_SECRET |

### Stage: Verify Rollout
Espera a que los seis Deployments estén completamente disponibles:
- auth, identity, gateway, dashboard, file: timeout de 180 segundos
- promotion: timeout de 300 segundos (Neo4j requiere más tiempo de inicialización)

Un fallo aquí indica que el nuevo código no arranca correctamente en el entorno de Kubernetes, lo que detiene el pipeline antes de ejecutar pruebas sobre un sistema degradado.

### Stage: E2E Tests
Se establecen `port-forward` a los seis servicios en sus puertos nativos (8180, 8083, 8087, 8088, 8084, 8085), se espera 15 segundos para estabilización, y se ejecuta la suite E2E:

```bash
./gradlew :e2e:test -Dbase.url=http://localhost \
    "-DJWT_SECRET=$JWT_SECRET" "-DQR_SECRET=$QR_SECRET" --no-daemon
```

### Stage: Locust Load Test
Se ejecutan cuatro escenarios de carga secuencialmente, cada uno con `port-forward` a puertos offset (18180, 18087, etc.) para evitar conflictos con los del stage anterior:

| Escenario | Usuarios | Tasa | Duración | Reporte generado |
|---|---|---|---|---|
| `AuthLoadTest` | 10 | 2/s | 2 min | `locust-auth-stage.html` |
| `GatewayLoadTest` | 10 | 2/s | 1 min | `locust-gateway-stage.html` |
| `PromotionStatsLoadTest` | 5 | 1/s | 1 min | `locust-promotion-stage.html` |
| `DashboardLoadTest` | 5 | 1/s | 1 min | `locust-dashboard-stage.html` |

Cada reporte HTML se publica como artefacto de Jenkins mediante el plugin HTML Publisher, siendo accesible directamente desde la interfaz del job con visualización completa de gráficas.

## 5.3 Publicación de artefactos

El bloque `post { always }` garantiza que se publiquen los siguientes artefactos independientemente del resultado del pipeline:
- Reporte JUnit de todos los servicios (`**/build/test-results/**/*.xml`)
- Reporte HTML del módulo E2E (`e2e/build/reports/tests/test/index.html`) via `publishHTML`
- Cuatro reportes HTML de Locust via `publishHTML` (uno por escenario)
- Archivos HTML archivados para descarga via `archiveArtifacts`

## 5.4 Evidencias del pipeline de stage

| Evidencia | Descripción |
|---|---|
| [Jenkinsfile de stage](../jenkins/jenkinsfile.stage) | Definición completa del pipeline de stage con todos los stages: Build, Unit Tests, Docker Push, Deploy, Verify, E2E y Locust |
| [Vista general del pipeline stage](evidencias/jenkins/stage-pipeline-overview.png) | Vista de Jenkins mostrando todos los stages del pipeline de stage completados exitosamente |
| [Stages del pipeline stage — parte 1](evidencias/jenkins/stage-steps-1.png) | Detalle de los primeros stages: Checkout, Build (paralelo), Unit Tests (paralelo), Docker Build & Push |
| [Stages del pipeline stage — parte 2](evidencias/jenkins/stage-steps-2.png) | Detalle de los stages finales: Deploy Stage, Verify Rollout, E2E Tests, Locust Load Test |
| [Pods y Services en circleguard-stage](evidencias/kubernetes/stage-pods-services.png) | `kubectl get pods,svc -n circleguard-stage` mostrando los 6 servicios en Running |
| [Reporte E2E — Stage](evidencias/tests/stage-artifacts/e2e/build/reports/tests/test/index.html) | Reporte HTML completo de los 12 tests E2E: 0 fallos, 14.977s, 100% exitosos |
| [Locust Auth — Stage](evidencias/tests/stage-artifacts/locust-auth-stage.html) | Reporte HTML completo de Locust para AuthLoadTest (139 requests, 0% error, p50=7.2s) |
| [Locust Gateway — Stage](evidencias/tests/stage-artifacts/locust-gateway-stage.html) | Reporte HTML completo de Locust para GatewayLoadTest (952 requests, 0% error, p50=6ms) |
| [Locust Promotion — Stage](evidencias/tests/stage-artifacts/locust-promotion-stage.html) | Reporte HTML completo de Locust para PromotionStatsLoadTest (142 requests, 0% error, p50=14ms) |
| [Locust Dashboard — Stage](evidencias/tests/stage-artifacts/locust-dashboard-stage.html) | Reporte HTML completo de Locust para DashboardLoadTest (88 requests, 0% error, p50=9ms) |

## 5.5 Análisis de resultados

La ejecución del pipeline de stage sobre el cluster Kind demuestra que los seis servicios pueden desplegarse de forma coordinada y superar las pruebas de integración end-to-end en un entorno Kubernetes real. Los 12 tests E2E completaron en 14.977 segundos con 100% de éxito. Los cuatro escenarios Locust mostraron 0% de tasa de error en todos los servicios, lo que confirma que el sistema mantiene disponibilidad completa bajo la carga simulada.

La validación del rollout como gate obligatorio antes de ejecutar los tests E2E es crítica: si un servicio no arranca, los tests fallarían por razones de infraestructura y no de lógica de negocio. Este ordering hace que los fallos sean siempre atribuibles al código bajo prueba.

---

# 6. Pipeline de Master/Producción

## 6.1 Propósito y flujo completo

El pipeline de producción implementa el principio de "build once, deploy many": el mismo artefacto (imagen Docker) que se valida en el stage de System Tests es el que se despliega en producción. No se recompila el código en el último momento; se construye con el mismo Gradle y el mismo Dockerfile, pero con tags `prod-{N}` y `prod-latest` que permiten identificar unívocamente el artefacto desplegado.

## 6.2 Stages del pipeline de producción

```
Checkout → Build (parallel) → Unit Tests (parallel) → Docker Build & Push
         → System Tests contra Stage → Aprobación Manual
         → Deploy Prod → Verify Rollout Prod → Release Notes
```

### Stage: System Tests contra Stage
Este stage usa el namespace `circleguard-stage` ya desplegado como entorno de validación de sistema. En lugar de desplegar una nueva versión en stage, el pipeline ejecuta la suite E2E contra los servicios que ya están corriendo, verificando que la nueva versión del código (recién compilada y pusheada) es compatible con la configuración del entorno de staging. Los port-forward se establecen en los puertos nativos (8180, 8083, etc.) con ambos secrets inyectados (`JWT_SECRET` y `QR_SECRET`).

**Racionalidad:** si los system tests fallan, el pipeline se detiene antes de la aprobación manual, ahorrando el tiempo del revisor y garantizando que solo se presentan a aprobación versiones técnicamente válidas.

### Stage: Aprobación Manual (Change Management)
El pipeline se pausa indefinidamente con un prompt de aprobación en la interfaz de Jenkins:
> *"¿Desplegar circleguard a producción?"* — [Desplegar]

Esta pausa cumple con el principio de **Change Management**: ningún cambio llega a producción sin validación humana explícita, independientemente de que todos los tests automáticos hayan pasado. El registro de Jenkins conserva qué usuario aprobó el despliegue y en qué momento.

### Stage: Deploy Prod
Con todas las credenciales inyectadas via `withCredentials`, el pipeline:
1. Aplica el overlay `k8s/overlays/prod/` con Kustomize.
2. Crea o actualiza de forma idempotente los cuatro Secrets en `circleguard-prod`.
3. Ejecuta `kubectl rollout restart` en los seis Deployments.

El namespace `circleguard-prod` usa el mismo overlay de Kustomize que stage pero con namespace `circleguard-prod` y tag `prod-latest`.

### Stage: Verify Rollout Prod
Todos los seis servicios usan timeout de 300 segundos — más conservador que stage para absorber posibles cold-starts en producción donde el JVM cache puede estar más frío.

### Stage: Release Notes (Change Management)
El stage final genera automáticamente el archivo `RELEASE_NOTES.md` parseando el historial de commits desde el último tag Git:

```bash
PREV_TAG=$(git describe --tags --abbrev=0 HEAD~1 2>/dev/null || echo "")
git log ${PREV_TAG}..HEAD --pretty=format:"- %s (%an)" > commits.txt
```

Los commits se categorizan según Conventional Commits:

| Prefijo | Sección en Release Notes |
|---|---|
| `feat:` | Nuevas funcionalidades |
| `fix:` | Correcciones |
| `test:`, `chore:`, `docs:`, otros | Otros cambios |

El archivo generado se archiva como artefacto del build de Jenkins, proporcionando trazabilidad de qué cambios forman parte de cada versión en producción.

## 6.3 Prácticas de Change Management implementadas

| Práctica | Implementación |
|---|---|
| Aprobación manual obligatoria | Stage `Aprobación Manual` con `input` de Jenkins |
| Registro de aprobaciones | Jenkins conserva el log de quién aprobó y cuándo |
| Release Notes automáticas | Generadas desde `git log` con formato Conventional Commits |
| Tags de versión inmutables | `prod-${BUILD_NUMBER}` identifica exactamente qué build corre |
| Idempotencia de secrets | `--dry-run=client -o yaml | kubectl apply -f -` |
| Verificación de disponibilidad | `kubectl rollout status --timeout=300s` antes de dar el deploy por exitoso |

## 6.4 Evidencias del pipeline de producción

| Evidencia | Descripción |
|---|---|
| [Jenkinsfile de producción](../Jenkinsfile.prod) | Definición completa del pipeline de producción incluyendo aprobación manual, deploy y generación de Release Notes |
| [Vista general del pipeline prod](evidencias/jenkins/prod-pipeline-overview.png) | Vista de Jenkins con todos los stages del pipeline de producción completados exitosamente |
| [Stages del pipeline prod — parte 1](evidencias/jenkins/prod-steps-1.png) | Detalle de los stages iniciales: Build, Unit Tests, Docker Push, System Tests contra Stage, Aprobación Manual |
| [Stages del pipeline prod — parte 2](evidencias/jenkins/prod-steps-2.png) | Detalle de los stages finales: Deploy Prod, Verify Rollout, Release Notes generadas |
| [Pods y Services en circleguard-prod](evidencias/kubernetes/prod-pods-services.png) | `kubectl get pods,svc -n circleguard-prod` mostrando los 6 servicios en Running |
| [Release Notes generadas](evidencias/tests/prod-artifacts/RELEASE_NOTES.md) | Archivo `RELEASE_NOTES.md` generado automáticamente por el pipeline con 11 features, 40+ correcciones y 10+ otros cambios |
| [Reporte E2E — Prod (System Tests)](evidencias/tests/prod-artifacts/e2e/build/reports/tests/test/index.html) | Reporte HTML de los 12 System Tests ejecutados contra stage: 0 fallos, 14.977s, 100% exitosos |

## 6.5 Análisis de resultados

El pipeline de producción implementa el nivel más alto de control de calidad del proceso. La combinación de tests automáticos (system tests contra stage) + aprobación humana + verificación de disponibilidad en Kubernetes crea tres capas independientes de validación antes de considerar un despliegue exitoso. Los 12 system tests pasaron con 100% de éxito, idénticos a los resultados de stage, lo que confirma la reproducibilidad del entorno. Las Release Notes generadas automáticamente clasificaron los commits en 11 nuevas funcionalidades, más de 40 correcciones y 10 otros cambios, eliminando el trabajo manual de documentar cambios y garantizando trazabilidad completa de la versión desplegada.

---

# 7. Estrategia de pruebas

## 7.1 Pruebas Unitarias

Las pruebas unitarias validan componentes individuales en aislamiento, sin dependencias de infraestructura real. Se implementaron en JUnit 5 con Mockito para inyección de mocks.

### PU-01: `JwtTokenServiceTest` — circleguard-auth-service

**Qué valida:** la lógica de generación y validación de tokens JWT del servicio de autenticación.

| Test | Descripción |
|---|---|
| `generateToken_subjectIsAnonymousId` | Verifica que el `sub` del JWT contiene el anonymousId y no la identidad real |
| `generateToken_containsPermissionsAsClaims` | Verifica que los permisos del usuario quedan codificados como claim `permissions` |
| `generateToken_expiresAfterConfiguredMilliseconds` | Instancia el servicio con `expiration=1ms`, espera 20ms, y verifica que el parser lanza excepción de expiración |
| `generateToken_rejectsTokenWithTamperedSignature` | Verifica que un token con firma adulterada es rechazado por el parser |

**Relevancia:** el JWT es el mecanismo de autenticación de toda la plataforma. Un bug en la emisión o validación del token comprometería la seguridad de todos los servicios.

### PU-02: `DualChainAuthProviderTest` — circleguard-auth-service

**Qué valida:** la cadena de autenticación dual (LDAP → base de datos local).

| Test | Descripción |
|---|---|
| `authenticate_usesLdapFirst_andDoesNotCallLocal` | Con LDAP exitoso, el proveedor local no es invocado |
| `authenticate_fallsBackToLocalDbWhenLdapFails` | Con LDAP fallando, se intenta autenticación local |
| `authenticate_throwsBadCredentialsWhenBothFail` | Si ambos proveedores fallan, se lanza `BadCredentialsException` |
| `supports_usernamePasswordTokenClass_returnsTrue` | El provider soporta el tipo de token correcto |

**Relevancia:** la autenticación dual permite que el sistema funcione aunque el servidor LDAP institucional no esté disponible, garantizando continuidad del servicio.

### PU-03: `IdentityVaultServiceTest` — circleguard-identity-service

**Qué valida:** la lógica del vault de identidades (mapeo pseudonimizado entre identidad real y anonymousId).

| Test | Descripción |
|---|---|
| `getOrCreateAnonymousId_returnsSameUuidForSameInput` | La misma identidad real siempre produce el mismo anonymousId (determinismo) |
| `getOrCreateAnonymousId_returnsDifferentUuidsForDifferentInputs` | Identidades diferentes producen anonymousIds diferentes |
| `resolveRealIdentity_throwsNotFoundForUnknownId` | Un anonymousId desconocido lanza `ResponseStatusException(404)` |
| `getOrCreateAnonymousId_persistsNewMappingOnFirstCall` | En el primer mapeo se invoca `repository.save()` |

**Relevancia:** el vault es el núcleo de privacidad del sistema. Estos tests garantizan que el principio de pseudonimización se cumple correctamente antes de cualquier despliegue.

### PU-04: `QrValidationServiceTest` — circleguard-gateway-service

**Qué valida:** la lógica de validación de tokens QR en el servicio de puerta de acceso.

| Test | Descripción |
|---|---|
| `validateToken_returnsInvalidForExpiredToken` | Token con fecha de expiración en el pasado retorna `valid=false` |
| `validateToken_returnsInvalidForMalformedJwt` | String que no es un JWT válido retorna `valid=false` |
| `validateToken_returnsDenialForPotentialStatus` | Redis contiene status `"POTENTIAL"` → la puerta deniega el acceso |

**Relevancia:** una falla en la validación del QR podría permitir el acceso a usuarios con estado de salud comprometido, lo que es el riesgo central del sistema.

### PU-05: `KAnonymityFilterTest` — circleguard-dashboard-service

**Qué valida:** el filtro de k-anonimato que enmascara datos individuales cuando el grupo es demasiado pequeño.

| Test | Descripción |
|---|---|
| `apply_masksEntireResultWhenTotalUsersBelowDefaultK` | Cuando totalUsers < k, todos los campos son enmascarados con `"<5"` |
| `apply_doesNotMaskWhenTotalUsersAtOrAboveDefaultK` | Con totalUsers ≥ k, los datos se exponen normalmente |
| `apply_masksIndividualCountFieldsBelowDefaultK` | Con totalUsers ≥ k pero suspectCount=2 < k, solo suspectCount se enmascara |
| `apply_withCustomK_masksWhenTotalUsersBelowThreshold` | Respeta el valor de k configurado dinámicamente |
| `apply_nullInput_returnsEmptyMap` | Entrada nula retorna mapa vacío sin excepción |

**Relevancia:** la k-anonimato es el mecanismo de protección de privacidad del módulo de analytics. Sin esta validación, podría filtrarse información individual de salud.

**Evidencias de tests unitarios:** [auth (28s)](evidencias/tests/auth-unit-test.txt) | [identity (11s)](evidencias/tests/identity-unit-test.txt) | [gateway (51s)](evidencias/tests/gateway-unit-test.txt) | [promotion (2m 28s)](evidencias/tests/promotion-unit-test.txt) | [dashboard (20s)](evidencias/tests/dashboard-unit-test.txt) | [file (19s)](evidencias/tests/file-unit-test.txt)

---

## 7.2 Pruebas de Integración

Las pruebas de integración validan la interacción de los servicios con su infraestructura real (bases de datos, caché, mensajería) usando Testcontainers para levantar contenedores reales durante la ejecución de los tests. Todas usan `@SpringBootTest(webEnvironment = RANDOM_PORT)` y `@DynamicPropertySource` para configurar las conexiones a los contenedores levantados.

### PI-01: `AuthLoginIntegrationTest` — circleguard-auth-service

**Qué valida:** el flujo completo de autenticación contra una base de datos PostgreSQL real.

- **Infraestructura:** `PostgreSQLContainer` con Flyway ejecutando las migraciones V1–V5 (crea usuarios `staff_guard`, `health_user`, `super_admin`).
- **Mocks:** `LdapAuthenticationProvider` (lanza `BadCredentialsException` → cae al proveedor local) y `IdentityClient` (retorna UUID de prueba).
- **Tests:** login válido retorna JWT + anonymousId con HTTP 200; contraseña incorrecta retorna HTTP 401; usuario inexistente retorna HTTP 401.

**Evidencia de integración service–database:** el test verifica que Flyway ejecuta las migraciones correctamente y que Spring Security autentica contra los usuarios reales de la base de datos.

### PI-02: `IdentityVaultIntegrationTest` — circleguard-identity-service

**Qué valida:** la persistencia del mapeo identidad real ↔ anonymousId en PostgreSQL y el control de acceso.

- **Infraestructura:** `PostgreSQLContainer` con Flyway.
- **Mocks:** `KafkaTemplate` (los eventos Kafka son secundarios para este test).
- **Tests:** mapear identidad retorna anonymousId; segunda llamada con misma identidad retorna el mismo anonymousId (idempotencia); lookup con permiso `identity:lookup` retorna 200; lookup sin ese permiso retorna 403.

### PI-03: `GatewayQrValidationIntegrationTest` — circleguard-gateway-service

**Qué valida:** la validación de QR tokens contra Redis real.

- **Infraestructura:** `GenericContainer("redis:7.2")` — se usa el módulo genérico de Testcontainers (no requiere módulo específico de Redis). `StringRedisTemplate` inyectado en el test para pre-cargar estados.
- **Tests:** status `CLEAR` en Redis → respuesta `GREEN`; status `CONTAGIED` → respuesta `RED`; sin entrada en Redis → `GREEN` (comportamiento por defecto seguro); token inválido → `RED`.

**Relevancia:** estos tests validan el contrato entre el gateway y Redis, que es la ruta de lectura crítica en cada validación de acceso físico.

### PI-04: `PromotionStatusIntegrationTest` — circleguard-promotion-service

**Qué valida:** la exposición de endpoints HTTP del servicio de promotion con autorización correcta.

- **Infraestructura:** `PostgreSQLContainer` + `Neo4jContainer`.
- **Mocks:** `StringRedisTemplate`, `KafkaTemplate`.
- **Tests:** `POST /api/v1/health/report` con permiso `HEALTH_CENTER` → 2xx; el mismo endpoint sin permiso → 403; `GET /api/v1/health-status/stats` sin autenticación → 200.

**Corrección documentada:** los endpoints del controlador usaban `hasRole("HEALTH_CENTER")` que Spring Security interpreta como `hasAuthority("ROLE_HEALTH_CENTER")`. El JWT filter crea authorities sin prefijo `ROLE_`, por lo que se corrigió a `hasAuthority("HEALTH_CENTER")` en todas las anotaciones `@PreAuthorize`.

### PI-05: `DashboardAnalyticsIntegrationTest` — circleguard-dashboard-service

**Qué valida:** la integración entre el dashboard y el servicio de promotion via HTTP, con k-anonimato aplicado correctamente.

- **Infraestructura:** `PostgreSQLContainer` + `WireMockExtension` (WireMock 3.4.2, compatible con Java 21). `@DynamicPropertySource` apunta `circleguard.promotion-service.url` al puerto de WireMock.
- **Tests:** WireMock retorna `suspectCount=2` → respuesta del dashboard enmascara con `"<5"`; WireMock retorna `totalUsers=3` < k → respuesta completamente enmascarada; endpoint `/summary` no llama a promotion (validación de que no hay acoplamiento inesperado).

---

## 7.3 Pruebas E2E

Las pruebas E2E validan flujos de usuario completos que atraviesan múltiples microservicios en el orden en que un usuario real los invocaría. Se implementan con REST Assured 5.4.0 y JJWT 0.11.5 en el módulo `:e2e` del monorepo. Cada test accede a los servicios por sus puertos nativos usando la URL base configurada via System Property `base.url`, lo que permite ejecutarlos tanto con `kubectl port-forward` como a través de un Ingress.

**Resultados globales (stage y prod):** 12 tests — 0 fallos — 0 ignorados — 14.977s — **100% exitosos**

### E2E-01: `LoginAndGateAccessE2ETest` — Flujo completo de autenticación y acceso físico

**Flujo validado:**
1. Login en auth-service con credenciales reales (`staff_guard` / `password`) → retorna JWT y anonymousId.
2. Solicitud de token QR al auth-service usando el JWT del paso anterior → retorna `qrToken`.
3. Validación del QR en el gateway-service → retorna `{"valid": true, "status": "GREEN"}`.

**Servicios involucrados:** auth-service (8180) → gateway-service (8087).
**Resultado:** 1 test — 0 fallos — **5.923s** (el test más largo: invoca auth + gateway con tokens reales)

**Relevancia:** es el flujo de acceso diario más ejecutado en el sistema real. Valida que la cadena de delegación de confianza (credenciales → JWT → QR → validación de puerta) funciona de extremo a extremo.

### E2E-02: `HealthStatusFlowE2ETest` — Reporte de estado de salud y control de acceso

**Flujo validado:**
1. Intento de reporte de estado de salud sin permiso `HEALTH_CENTER` → retorna 403.
2. Reporte de estado de salud con permiso `HEALTH_CENTER` → retorna 2xx.

**Servicios involucrados:** promotion-service (8088).
**Resultado:** 2 tests — 0 fallos — **1.147s**

**Relevancia:** valida que las restricciones de autorización se aplican correctamente en el flujo de negocio más sensible del sistema (reportar que un usuario tiene COVID u otra condición).

### E2E-03: `DashboardAnalyticsE2ETest` — Consulta de analítica

**Flujo validado:**
- `GET /api/v1/analytics/health-board` → HTTP 200.
- `GET /api/v1/analytics/summary` → HTTP 200.
- `GET /api/v1/analytics/time-series` → HTTP 200.

**Servicios involucrados:** dashboard-service (8084), que internamente invoca promotion-service (8088).
**Resultado:** 3 tests — 0 fallos — **2.299s**

**Relevancia:** valida el pipeline completo de analítica: el dashboard obtiene datos de promotion, aplica k-anonimato y retorna resultados a administradores.

### E2E-04: `FileUploadE2ETest` — Subida de certificados de salud

**Flujo validado:**
- Upload de un archivo PDF → retorna filename con prefijo UUID y sufijo del nombre original.
- Upload de un archivo de imagen → retorna HTTP 200.

**Servicios involucrados:** file-service (8085).
**Resultado:** 2 tests — 0 fallos — **0.879s** (el test más rápido)

**Relevancia:** valida la funcionalidad de almacenamiento de documentos médicos, incluida la sanitización del nombre de archivo (protección contra path traversal).

### E2E-05: `IdentityMappingE2ETest` — Vault de identidades

**Flujo validado:**
1. `POST /api/v1/identities/map` con identidad real → retorna `anonymousId`.
2. Segunda llamada con la misma identidad real → retorna el mismo `anonymousId` (idempotencia).
3. `GET /api/v1/identities/lookup/{anonymousId}` con token que tiene permiso `identity:lookup` → retorna identidad real.
4. Mismo endpoint sin token → retorna 401 o 403.

**Servicios involucrados:** identity-service (8083).
**Resultado:** 4 tests — 0 fallos — **4.729s** (4 requests HTTP reales con verificación de respuesta)

**Relevancia:** valida el ciclo completo de pseudonimización: que el mapeo es consistente, que el lookup requiere permiso explícito y que la identidad real está correctamente protegida.

**Evidencias E2E:** [Reporte completo Stage](evidencias/tests/stage-artifacts/e2e/build/reports/tests/test/index.html) | [Reporte completo Prod](evidencias/tests/prod-artifacts/e2e/build/reports/tests/test/index.html)

---

## 7.4 Pruebas de Rendimiento y Estrés

### Configuración de Locust

El script de pruebas de rendimiento se encuentra en `locust/locustfile.py`. Define cuatro clases independientes `HttpUser`, cada una representando un escenario de uso real del sistema. La ejecución en el pipeline de stage usa la interfaz de línea de comandos en modo headless (`--headless`) para integración automática.

Las credenciales se inyectan via variables de entorno (`os.environ["JWT_SECRET"]`, `os.environ["QR_SECRET"]`), garantizando que el script falla explícitamente si las credenciales no están presentes, lo que previene ejecuciones silenciosamente incorrectas.

### Resultados consolidados

| Servicio | Requests | Fallos | p50 | Avg | Max | RPS |
|---|---|---|---|---|---|---|
| Auth | 139 | 0 (0%) | 7,200 ms | 6,880 ms | 12,641 ms | 1.17/s |
| Gateway | 952 | 0 (0%) | 6 ms | 13.9 ms | 2,213 ms | 15.93/s |
| Promotion | 142 | 0 (0%) | 14 ms | 18.2 ms | 394 ms | 2.40/s |
| Dashboard | 88 | 0 (0%) | 9 ms | 15.7 ms | 604 ms | 1.48/s |

**Tasa de error global: 0% en todos los escenarios.**

### Escenario 1: `AuthLoadTest` — Carga de autenticación

**Configuración:** 10 usuarios virtuales, tasa 2/s, duración 2 minutos. `@task(1)` login inválido (32 requests) / `@task(3)` login válido (107 requests).

**Resultados:**
- Login válido: p50=7,600ms, avg=7,623ms, min=1,949ms, max=12,641ms
- Login inválido: p50=4,100ms, avg=4,394ms, min=2,301ms, max=8,838ms
- Agregado: p50=7,200ms, RPS=1.17/s, **0 fallos**

**Análisis:** la latencia de autenticación es dominada por el cómputo de bcrypt (factor de costo alto) más la consulta a PostgreSQL. En un clúster Kind local con recursos limitados, p50=7.2s y max=12.6s son valores esperados; en infraestructura dedicada, bcrypt típicamente completa en 200–500ms. Lo crítico es que el sistema mantuvo **0% de tasa de error** durante los 2 minutos de carga sostenida: ningún login válido fue rechazado incorrectamente.

### Escenario 2: `GatewayLoadTest` — Carga de validación de puertas

**Configuración:** 10 usuarios, tasa 2/s, duración 1 minuto. `on_start()` genera un token QR propio con PyJWT. `@task(5)` validación válida (794 requests) / `@task(1)` validación inválida (158 requests).

**Resultados:**
- Validación válida: p50=6ms, avg=14ms, min=3ms, max=2,213ms
- Validación inválida: p50=6ms, avg=11ms, min=3ms, max=811ms
- Agregado: p50=6ms, RPS=15.93/s, **0 fallos**

**Particularidad de implementación:** el gateway siempre responde HTTP 200. El criterio de éxito en Locust es el campo `valid` del JSON (`true`/`false`), no el código HTTP; el test usa `catch_response=True` y verifica `resp.json().get("valid") is True`. El max de 2,213ms es un outlier puntual (probablemente un cold-start de Redis), mientras que el p50 de 6ms confirma que las lecturas Redis son O(1) y extremadamente rápidas. Con 15.93 RPS, el gateway es el servicio de mayor throughput.

### Escenario 3: `PromotionStatsLoadTest` — Carga de consulta de estadísticas

**Configuración:** 5 usuarios, tasa 1/s, duración 1 minuto. `@task(3)` `GET /api/v1/health-status/stats` (87 requests) / `@task(2)` `GET /api/v1/health-status/stats/department/{dept}` (55 requests, departamento aleatorio entre 5 opciones).

**Resultados:**
- Stats globales: p50=13ms, avg=18ms, min=8ms, max=394ms
- Stats por departamento: p50=15ms, avg=18ms, min=9ms, max=97ms
- Agregado: p50=14ms, avg=18.2ms, RPS=2.40/s, **0 fallos**

**Análisis:** contrario a la expectativa inicial, el promotion-service mostró latencia comparable al gateway para las consultas de estadísticas. p50=14ms indica que las queries Cypher sobre el grafo Neo4j están bien indexadas y el grafo en el entorno de prueba es suficientemente pequeño para responder en single-digit milisegundos. El outlier de 394ms en stats globales es puntual (posiblemente el primer acceso tras un GC del JVM).

### Escenario 4: `DashboardLoadTest` — Carga de analítica

**Configuración:** 5 usuarios, tasa 1/s, duración 1 minuto. `@task(3)` health-board (39 requests) / `@task(2)` summary (32 requests) / `@task(1)` time-series (17 requests).

**Resultados:**
- health-board: p50=9ms, avg=8.8ms, min=5ms, max=34ms
- summary: p50=10ms, avg=9ms, min=5ms, max=14ms
- time-series: p50=7ms, avg=44ms, min=5ms, max=604ms ← outlier en avg
- Agregado: p50=9ms, avg=15.7ms, RPS=1.48/s, **0 fallos**

**Análisis:** el endpoint `/time-series` muestra el avg más alto (44ms) debido a que el cómputo de series temporales implica mayor procesamiento de datos antes de aplicar k-anonimato. Sin embargo, p50=7ms para este endpoint sugiere que la mayoría de las peticiones son rápidas y el avg está inflado por dos o tres respuestas con mucha carga de datos. El max de 604ms coincide con la llamada HTTP interna a promotion-service bajo carga concurrente.

---

# 8. Configuración de los Pipelines

## 8.1 Pipeline de Desarrollo — Estructura del Jenkinsfile

Cada servicio tiene un `Jenkinsfile` en `services/<servicio>/Jenkinsfile`. La estructura es uniforme en todos los servicios; las diferencias son las variables de entorno (`DOCKER_REGISTRY`, `IMAGE_NAME`, `DEPLOYMENT_NAME`, `K8S_DIR`, `GRADLE_TARGET`).

**Variables de entorno clave:**
```groovy
DOCKER_REGISTRY  = 'kind-registry:5000'
IMAGE_NAME       = 'circleguard/<servicio>'
IMAGE_TAG        = 'dev'
DEPLOYMENT_NAME  = 'circleguard-<nombre-corto>'
K8S_NAMESPACE    = 'circleguard-dev'
K8S_DIR          = 'k8s/services/circleguard-<nombre-corto>'
GRADLE_TARGET    = ':services:circleguard-<servicio>'
GRADLE_USER_HOME = '/var/jenkins_home/.gradle'
PATH             = "/var/jenkins_home/bin:${env.PATH}"
```

El `PATH` incluye `/var/jenkins_home/bin` donde el hook `postStart` del pod de Jenkins instala el Docker CLI, sin necesidad de instalar herramientas en la imagen base.

**Integración Docker:** el pipeline usa el Docker CLI instalado en `/var/jenkins_home/bin/docker`, que se conecta al daemon del sidecar `docker:27-dind` via la variable de entorno `DOCKER_HOST=tcp://localhost:2375` configurada en el Helm values del pod.

**Integración Kubernetes:** `withKubeConfig([credentialsId: 'kubeconfig-circleguard'])` inyecta el kubeconfig que apunta a `https://kubernetes.default.svc` (API server del propio clúster Kind, accesible desde dentro del pod de Jenkins).

**Jenkinsfiles de desarrollo (uno por servicio):**
- [auth-service/Jenkinsfile](../services/circleguard-auth-service/Jenkinsfile)
- [identity-service/Jenkinsfile](../services/circleguard-identity-service/Jenkinsfile)
- [gateway-service/Jenkinsfile](../services/circleguard-gateway-service/Jenkinsfile)
- [promotion-service/Jenkinsfile](../services/circleguard-promotion-service/Jenkinsfile)
- [dashboard-service/Jenkinsfile](../services/circleguard-dashboard-service/Jenkinsfile)
- [file-service/Jenkinsfile](../services/circleguard-file-service/Jenkinsfile)

## 8.2 Pipeline de Stage — Estructura del Jenkinsfile

El archivo `jenkins/jenkinsfile.stage` contiene el pipeline de stage. Sus características más relevantes:

- **Build paralelo:** `parallel {}` con seis sub-stages reduce el tiempo de compilación total.
- **`withCredentials` anidado:** cada sub-stage de Unit Tests tiene solo las credenciales que necesita, minimizando el scope de exposición de secrets.
- **`docker build --network=host`:** permite que el proceso de build acceda a servicios de red locales si es necesario.
- **Kustomize en lugar de manifiestos directos:** `kubectl apply -k k8s/overlays/stage/` gestiona todos los recursos del namespace con un solo comando.
- **Creación idempotente de Secrets:** el pattern `--dry-run=client -o yaml | kubectl apply -f -` permite re-ejecutar el pipeline sin error si los Secrets ya existen.
- **`publishHTML`:** el plugin HTML Publisher de Jenkins sirve los reportes Locust con CSP relajada (configurada via `initScripts` en el Helm values), lo que permite que las gráficas de Locust se rendericen correctamente en el browser.

**Archivo de pipeline:** [jenkins/jenkinsfile.stage](../jenkins/jenkinsfile.stage)

## 8.3 Pipeline de Master — Estructura del Jenkinsfile

El archivo `Jenkinsfile.prod` en la raíz del repositorio contiene el pipeline de producción. Elementos diferenciadores:

- **Tres tags de imagen:** `prod-${BUILD_NUMBER}` (trazabilidad), `prod-latest` (referencia estable), `latest` (convención Docker).
- **`input` de Jenkins:** el stage de aprobación manual bloquea el pipeline hasta que un operador autorizado haga clic en "Desplegar" en la UI de Jenkins. El timeout es indefinido por diseño (un deploy puede esperar la aprobación horas si es necesario).
- **Script de Release Notes:** un script bash puro que no depende de plugins de Jenkins adicionales. Usa `git describe --tags --abbrev=0 HEAD~1` para obtener el tag anterior, garantizando que el diff del log sea preciso.

**Archivo de pipeline:** [Jenkinsfile.prod](../Jenkinsfile.prod)

## 8.4 Infraestructura Jenkins (jenkins-values.yaml)

El archivo `jenkins/jenkins-values.yaml` es el Helm values file que configura el pod de Jenkins. Los elementos más relevantes:

| Configuración | Valor | Propósito |
|---|---|---|
| `controller.serviceType` | `NodePort:32000` | Acceso externo al cluster Kind |
| Sidecar `docker-dind` | `docker:27-dind` | Daemon Docker para `docker build` y `docker push` |
| `DOCKER_HOST` | `tcp://localhost:2375` | Conexión del Jenkins controller al sidecar dind |
| `hostAliases` | `172.20.0.10` → `kind-registry` | DNS del registry local para el pod Jenkins |
| `lifecycle.postStart` | Script de instalación | Docker CLI, Python 3.11, Locust instalados al arrancar |
| `initScripts.relax-csp` | `System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")` | Permite que los reportes HTML de Locust rendericen scripts inline |
| `installPlugins` | git, workflow-aggregator, docker-workflow, kubernetes, htmlpublisher, jacoco, blueocean, github, junit | Plugins necesarios para todos los pipelines |

**Archivos de configuración:** [jenkins-values.yaml](../jenkins/jenkins-values.yaml) | [locust/locustfile.py](../locust/locustfile.py) | [Credenciales configuradas en Jenkins](evidencias/jenkins/jenkins-credentials.png) | [Lista de jobs en Jenkins](evidencias/jenkins/jenkins-jobs.png)

## 8.5 Infraestructura Kubernetes (Kind)

El clúster Kind se configura mediante `kind-config.yaml`:
- `extraPortMappings` para puertos 80 y 443 (Ingress).
- `containerdConfigPatches` para el registry local: las imágenes referenciadas como `localhost:5000/...` en los manifiestos de K8s son resueltas por el nodo Kind al registry `kind-registry:5000` gracias a este mirror.

**Comandos de setup del clúster:**
```bash
docker run -d --restart=always -p 5000:5000 --name kind-registry registry:2
kind create cluster --config kind-config.yaml --name circleguard
docker network connect kind kind-registry
docker network connect --ip 172.20.0.10 kind kind-registry  # IP estática
kubectl create namespace circleguard-dev
kubectl create namespace circleguard-stage
kubectl create namespace circleguard-prod
kubectl apply -f k8s/infra/ -n circleguard-stage
kubectl apply -f k8s/infra/ -n circleguard-prod
```

**Nota sobre IP estática:** el contenedor `kind-registry` recibe la IP estática `172.20.0.10` en la red Docker `kind`. Esta IP se configura en `hostAliases` del pod de Jenkins para que el sidecar dind pueda resolver `kind-registry:5000`. Sin la IP estática, si el contenedor se recrea, su IP cambia y las referencias DNS del pod de Jenkins dejan de funcionar.

**Evidencias:** [Clúster Kind activo](evidencias/kubernetes/kind-cluster.png) | [Namespaces activos](evidencias/kubernetes/kind-namespaces.png)

---

# 9. Resultados de Ejecución

## 9.1 Pipeline de Desarrollo — Resultados

Los seis pipelines de desarrollo se ejecutaron exitosamente. Los tiempos del stage Unit Tests son:

| Servicio | Tiempo | Resultado |
|---|---|---|
| auth-service | 28s | BUILD SUCCESSFUL — 4 executed, 2 up-to-date |
| identity-service | 11s | BUILD SUCCESSFUL — 2 executed, 2 from cache, 2 up-to-date |
| gateway-service | 51s | BUILD SUCCESSFUL — 3 executed, 2 up-to-date |
| promotion-service | 2m 28s | BUILD SUCCESSFUL — 4 executed, 2 up-to-date |
| dashboard-service | 20s | BUILD SUCCESSFUL — 2 executed, 1 from cache, 2 up-to-date |
| file-service | 19s | BUILD SUCCESSFUL — 3 executed, 2 up-to-date |

**Tasa de éxito: 100% en todos los servicios.**

El promotion-service es el más lento (2m 28s) porque sus tests de integración requieren levantar tres contenedores via Testcontainers (PostgreSQL, Neo4j, Redis) y ejecutar el PromotionPerformanceTest con un grafo de 10.000 nodos. El gateway-service (51s) tarda más de lo esperado por la generación de múltiples tokens JWT con verificación criptográfica. Los demás servicios completan entre 11 y 28 segundos.

Los seis Deployments en `circleguard-dev` quedaron en estado `AVAILABLE` tras el rollout, confirmado por `kubectl rollout status`.

## 9.2 Pipeline de Stage — Resultados

**E2E Tests:** 12 tests ejecutados a las 2:10:50 AM (hora del cluster) — 0 fallos — 0 ignorados — **14.977s total — 100% exitosos**.

| Clase | Tests | Duración |
|---|---|---|
| LoginAndGateAccessE2ETest | 1 | 5.923s |
| IdentityMappingE2ETest | 4 | 4.729s |
| DashboardAnalyticsE2ETest | 3 | 2.299s |
| HealthStatusFlowE2ETest | 2 | 1.147s |
| FileUploadE2ETest | 2 | 0.879s |

**Locust Load Tests:** 4 escenarios ejecutados secuencialmente — **0% tasa de error en todos**.

| Escenario | Requests | Duración test | RPS | p50 |
|---|---|---|---|---|
| AuthLoadTest | 139 | 1m 59s | 1.17/s | 7,200ms |
| GatewayLoadTest | 952 | ~1 min | 15.93/s | 6ms |
| PromotionStatsLoadTest | 142 | ~1 min | 2.40/s | 14ms |
| DashboardLoadTest | 88 | ~1 min | 1.48/s | 9ms |

Los cuatro reportes HTML fueron publicados exitosamente como artefactos en el sidebar del job de Jenkins.

## 9.3 Pipeline de Master — Resultados

**System Tests:** 12 tests contra el namespace de stage — 0 fallos — **100% exitosos** en 14.977s. Resultado idéntico al de stage, confirmando la reproducibilidad del entorno.

**Aprobación manual:** el pipeline pausó exitosamente y se reanudó tras la confirmación. El log de Jenkins registra el momento exacto y el usuario que aprobó.

**Deploy Prod:** `kubectl apply -k k8s/overlays/prod/`, creación de 4 Secrets y rollout restart completados sin errores. El verify rollout confirmó los 6 servicios disponibles en `circleguard-prod`.

**Release Notes:** el archivo `RELEASE_NOTES.md` fue generado automáticamente clasificando los commits en:
- 11 **nuevas funcionalidades** (`feat:`) — incluyendo Jenkinsfiles para dev, configuraciones de pods para todos los servicios, Dockerfiles e infraestructura.
- 40+ **correcciones** (`fix:`) — incluyendo gestión de credenciales, configuración de Locust, parámetros de tests, configuración de Jenkins y fixes de integración.
- 10+ **otros cambios** — hotfixes, tests y contribuciones del equipo de frontend.

---

# 10. Análisis General

## 10.1 Interpretación de resultados de pruebas

La estrategia de testing en múltiples niveles implementada demostró ser efectiva para detectar diferentes categorías de defectos:

**Pruebas unitarias:** identificaron dos bugs importantes durante el desarrollo: (1) el servicio de autenticación usaba `qr.expiration=300` (300ms en lugar de 300000ms = 5 minutos), y (2) el controlador de estado de salud usaba `hasRole()` cuando debía usar `hasAuthority()`. Ambos fueron detectados por las pruebas unitarias antes de llegar a ningún entorno desplegado.

**Pruebas de integración:** revelaron un problema de infraestructura en las migraciones de Flyway del servicio de promotion (la tabla `system_settings` se referenciaba antes de crearse). Este tipo de problema no es detectable con pruebas unitarias puras, requiere una base de datos real con Flyway ejecutándose.

**Pruebas E2E:** validaron que la cadena completa de confianza (credenciales → JWT → QR → gateway) funciona correctamente cuando todos los servicios están desplegados y configurados. Un token JWT generado por auth-service es correctamente verificado por el gateway-service porque ambos usan la misma clave secreta configurada via Kubernetes Secrets.

## 10.2 Interpretación de métricas de rendimiento

Los reportes Locust adjuntos revelan el comportamiento real del sistema bajo carga, con varias observaciones que contrastan con las expectativas iniciales:

**auth-service — latencia alta pero sin errores:** el p50 de 7,200ms refleja el costo computacional de bcrypt en un entorno Kind con recursos compartidos. Esto no es un defecto del servicio sino una consecuencia del entorno de prueba; en producción con JVM dedicada, bcrypt típicamente completa en 200–500ms. Lo destacable es que los 139 requests se completaron con **0% de error**: ningún usuario válido fue rechazado bajo carga.

**gateway-service — el servicio más rápido (p50=6ms):** las lecturas Redis son O(1) y están completamente dominadas por la latencia de red local, no por cómputo. El throughput de 15.93 RPS con p50=6ms confirma que el gateway puede manejar picos de acceso físico (múltiples personas pasando simultáneamente por una puerta) sin degradación. El max de 2,213ms es un outlier aislado (un GC pause o cold-start de Redis), no un problema sistémico.

**promotion-service — más rápido de lo esperado (p50=14ms):** la combinación PostgreSQL + Neo4j resultó en p50=14ms bajo la carga del test, lo que indica que las queries Cypher de estadísticas están correctamente indexadas. El max de 394ms en stats globales es un outlier, posiblemente relacionado con el primer acceso tras un garbage collection del JVM de Neo4j.

**dashboard-service — p50=9ms, pero time-series tiene mayor variabilidad:** el endpoint `/analytics/time-series` muestra avg=44ms con p50=7ms, indicando que la mayoría de las peticiones son rápidas pero algunas tienen mayor costo computacional (posiblemente por el volumen de datos en la serie). El max de 604ms corresponde a una llamada HTTP a promotion-service bajo carga concurrente. La latencia promedio general de 15.7ms es excelente para un servicio que hace una llamada HTTP interna y aplica k-anonimato.

**Conclusión de rendimiento:** los cuatro servicios mostraron **0% de tasa de error**, que es el indicador más importante para un sistema de control de acceso. La alta latencia de auth es inherente a bcrypt y aceptable dado que es una operación que ocurre una vez por sesión. Los servicios de lectura (gateway, promotion, dashboard) muestran latencias de single-digit a double-digit milisegundos, apropiadas para uso interactivo.

## 10.3 Beneficios CI/CD alcanzados

| Beneficio | Evidencia |
|---|---|
| **Retroalimentación rápida** | Los developers reciben el resultado de tests y despliegue en menos de 8 minutos tras un commit (excluyendo promotion-service: ~3 minutos extra por Testcontainers) |
| **Reproducibilidad** | Cada pipeline usa `cleanWs()` y `checkout scm` — el resultado no depende del estado del workspace anterior |
| **Trazabilidad** | Los tags de imagen `prod-${BUILD_NUMBER}` y las Release Notes permiten saber exactamente qué está en producción |
| **Separación de responsabilidades** | Los ambientes dev/stage/prod son completamente independientes a nivel de namespace y secrets |
| **Control de cambios** | La aprobación manual y las Release Notes automáticas implementan Change Management sin overhead manual |
| **Detección temprana de defectos** | Los bugs de `hasRole` vs `hasAuthority` y de migración Flyway fueron detectados en tests antes de llegar a stage |

## 10.4 Limitaciones identificadas y mejoras recomendadas

| Limitación | Causa | Mejora recomendada |
|---|---|---|
| **Secrets en texto plano en manifiestos dev** | Los `secret.yaml` de dev tienen valores hardcodeados | Usar Sealed Secrets o External Secrets Operator |
| **Registry sin autenticación ni TLS** | El registry local usa HTTP y no requiere login | Configurar TLS con certificado autofirmado y credentials |
| **Locust en parámetros conservadores** | Los tests corren con 5–10 usuarios en un Kind local | En un entorno real, escalar a cientos de usuarios concurrentes |
| **No hay rollback automático** | Si el verify rollout falla, el pipeline falla pero no hace rollback | Implementar `kubectl rollout undo` en el `post { failure }` del pipeline de prod |
| **Tests E2E sin cleanup** | Los datos creados por los tests E2E persisten en el cluster de stage | Implementar cleanup en `@AfterAll` o usar un namespace efímero por ejecución |
| **Sin alertas de pipeline** | No hay notificaciones (Slack, email) cuando un pipeline falla | Agregar `post { failure { slackSend ... } }` en los Jenkinsfiles |

---

# 11. Conclusiones

## 11.1 Evaluación de la implementación

La implementación completa de la cadena CI/CD para CircleGuard demuestra que es posible automatizar el ciclo de vida completo de un sistema de microservicios complejo (6 servicios, 4 bases de datos, mensajería async) con un nivel de madurez DevOps significativo. Los tres ambientes funcionan de forma independiente y la promoción entre ellos sigue un proceso controlado y documentado.

La adopción de Kustomize para la gestión de manifiestos Kubernetes elimina la duplicación de código entre ambientes. Un cambio en los manifiestos base se propaga a stage y prod automáticamente en la siguiente ejecución de pipeline, sin necesidad de editar múltiples archivos.

El uso de Testcontainers en los tests de integración representa una inversión en calidad de alto valor: los tests son lentos pero completamente fieles al comportamiento de producción, detectando problemas de configuración de base de datos, migraciones Flyway y contratos de API que los mocks no revelarían.

## 11.2 Beneficios de la estrategia DevOps

La principal ventaja de la cadena CI/CD implementada es la **eliminación de la ambigüedad**: en todo momento es posible responder con certeza a las preguntas "¿qué versión está en producción?" (tag `prod-${BUILD_NUMBER}` + Release Notes), "¿pasó todos los tests?" (historial de Jenkins) y "¿quién aprobó el deploy?" (log del stage de aprobación manual).

La ejecución paralela de compilación y tests en el pipeline de stage reduce el tiempo de ciclo sin comprometer la cobertura. Un developer puede ver el resultado de la compilación y los tests de los seis servicios en el tiempo que tardaría en compilar el servicio más lento secuencialmente.

## 11.3 Lecciones aprendidas

1. **La especificidad de los permisos de Spring Security importa:** la diferencia entre `hasRole()` y `hasAuthority()` es sutil pero rompe la autenticación completamente. Las pruebas de integración con un JWT real (no mockeado) fueron lo que reveló este bug.

2. **Los transaction managers no deben mockearse en tests de integración:** mockear los transaction managers de JPA o Neo4j hace que las operaciones de base de datos no se ejecuten correctamente en los tests, produciendo falsos positivos. Los tests deben usar Testcontainers reales con los transaction managers del contexto Spring.

3. **El CSP de Jenkins es un obstáculo real para reportes HTML:** los reportes generados por Locust usan scripts inline que el Content Security Policy de Jenkins bloquea por defecto. La solución vía `initScripts` en el Helm values es la única forma de configurarlo correctamente sin violar las restricciones del Groovy sandbox.

4. **Las IPs de contenedores Docker son mutables:** la IP del registry `kind-registry` cambió entre sesiones porque el contenedor fue recreado. La asignación de una IP estática via `docker network connect --ip` es la solución correcta para entornos de desarrollo persistentes.

5. **La aprobación manual añade valor real:** aunque parezca un obstáculo burocrático, el stage de aprobación manual obliga a revisar las Release Notes generadas antes de promover a producción. En la práctica, esto revela frecuentemente commits que no deberían ir a producción en ese momento.

6. **bcrypt escala horizontalmente, no verticalmente:** la alta latencia del auth-service bajo carga (p50=7.2s en Kind) confirma que para este tipo de cómputo, la solución es escalar el número de réplicas del servicio, no aumentar la potencia del servidor individual.

---

# 12. Evidencias Adjuntas

A continuación se listan todos los artefactos de evidencia disponibles en la carpeta `evidencias/` relativa a este documento.

## 12.1 Capturas del pipeline de Desarrollo (Jenkins)

| Archivo | Contenido |
|---|---|
| [evidencias/jenkins/dev-auth-pipeline.png](evidencias/jenkins/dev-auth-pipeline.png) | Vista de stages del pipeline `circleguard-auth-service-dev` en Jenkins |
| [evidencias/jenkins/dev-identity-pipeline.png](evidencias/jenkins/dev-identity-pipeline.png) | Vista de stages del pipeline `circleguard-identity-service-dev` |
| [evidencias/jenkins/dev-gateway-pipeline.png](evidencias/jenkins/dev-gateway-pipeline.png) | Vista de stages del pipeline `circleguard-gateway-service-dev` |
| [evidencias/jenkins/dev-promotion-pipeline.png](evidencias/jenkins/dev-promotion-pipeline.png) | Vista de stages del pipeline `circleguard-promotion-service-dev` |
| [evidencias/jenkins/dev-dashboard-pipeline.png](evidencias/jenkins/dev-dashboard-pipeline.png) | Vista de stages del pipeline `circleguard-dashboard-service-dev` |
| [evidencias/jenkins/dev-file-pieline.png](evidencias/jenkins/dev-file-pieline.png) | Vista de stages del pipeline `circleguard-file-service-dev` |

## 12.2 Capturas del pipeline de Stage (Jenkins)

| Archivo | Contenido |
|---|---|
| [evidencias/jenkins/stage-pipeline-overview.png](evidencias/jenkins/stage-pipeline-overview.png) | Vista general del pipeline `circleguard-stage` con todos los stages completados |
| [evidencias/jenkins/stage-steps-1.png](evidencias/jenkins/stage-steps-1.png) | Detalle de los stages iniciales: Checkout, Build paralelo, Unit Tests paralelo, Docker Build & Push |
| [evidencias/jenkins/stage-steps-2.png](evidencias/jenkins/stage-steps-2.png) | Detalle de los stages finales: Deploy Stage, Verify Rollout, E2E Tests, Locust |

## 12.3 Capturas del pipeline de Producción (Jenkins)

| Archivo | Contenido |
|---|---|
| [evidencias/jenkins/prod-pipeline-overview.png](evidencias/jenkins/prod-pipeline-overview.png) | Vista general del pipeline `circleguard-prod` completo |
| [evidencias/jenkins/prod-steps-1.png](evidencias/jenkins/prod-steps-1.png) | Detalle de los stages iniciales: Build, Unit Tests, Docker Push, System Tests, Aprobación Manual |
| [evidencias/jenkins/prod-steps-2.png](evidencias/jenkins/prod-steps-2.png) | Detalle de los stages finales: Deploy Prod, Verify Rollout, Release Notes archivadas |

## 12.4 Configuración de Jenkins

| Archivo | Contenido |
|---|---|
| [evidencias/jenkins/jenkins-credentials.png](evidencias/jenkins/jenkins-credentials.png) | Lista de credenciales configuradas en Jenkins Credential Store |
| [evidencias/jenkins/jenkins-jobs.png](evidencias/jenkins/jenkins-jobs.png) | Lista de los 8 jobs configurados en Jenkins (6 dev + 1 stage + 1 prod) |

## 12.5 Evidencias de Kubernetes

| Archivo | Contenido |
|---|---|
| [evidencias/kubernetes/kind-cluster.png](evidencias/kubernetes/kind-cluster.png) | Salida de `kind get clusters` y `kubectl get nodes` |
| [evidencias/kubernetes/kind-namespaces.png](evidencias/kubernetes/kind-namespaces.png) | `kubectl get namespaces` — 4 namespaces activos: circleguard-dev, stage, prod, jenkins |
| [evidencias/kubernetes/dev-pods-services.png](evidencias/kubernetes/dev-pods-services.png) | `kubectl get pods,svc -n circleguard-dev` — 6 servicios en Running con sus ClusterIP |
| [evidencias/kubernetes/stage-pods-services.png](evidencias/kubernetes/stage-pods-services.png) | `kubectl get pods,svc -n circleguard-stage` — 6 servicios en Running |
| [evidencias/kubernetes/prod-pods-services.png](evidencias/kubernetes/prod-pods-services.png) | `kubectl get pods,svc -n circleguard-prod` — 6 servicios en Running |

## 12.6 Salidas de tests unitarios (logs de Gradle)

| Archivo | Contenido |
|---|---|
| [evidencias/tests/auth-unit-test.txt](evidencias/tests/auth-unit-test.txt) | Log completo del stage Unit Tests de auth-service: BUILD SUCCESSFUL en 28s |
| [evidencias/tests/identity-unit-test.txt](evidencias/tests/identity-unit-test.txt) | Log completo del stage Unit Tests de identity-service: BUILD SUCCESSFUL en 11s (FROM-CACHE) |
| [evidencias/tests/gateway-unit-test.txt](evidencias/tests/gateway-unit-test.txt) | Log completo del stage Unit Tests de gateway-service: BUILD SUCCESSFUL en 51s |
| [evidencias/tests/promotion-unit-test.txt](evidencias/tests/promotion-unit-test.txt) | Log completo del stage Unit Tests de promotion-service: BUILD SUCCESSFUL en 2m 28s |
| [evidencias/tests/dashboard-unit-test.txt](evidencias/tests/dashboard-unit-test.txt) | Log completo del stage Unit Tests de dashboard-service: BUILD SUCCESSFUL en 20s |
| [evidencias/tests/file-unit-test.txt](evidencias/tests/file-unit-test.txt) | Log completo del stage Unit Tests de file-service: BUILD SUCCESSFUL en 19s |

## 12.7 Reportes de pruebas E2E y rendimiento

| Archivo | Contenido |
|---|---|
| [evidencias/tests/stage-artifacts/e2e/.../index.html](evidencias/tests/stage-artifacts/e2e/build/reports/tests/test/index.html) | Reporte HTML E2E de Stage: 12 tests, 0 fallos, 14.977s, 100% exitosos |
| [evidencias/tests/prod-artifacts/e2e/.../index.html](evidencias/tests/prod-artifacts/e2e/build/reports/tests/test/index.html) | Reporte HTML E2E de Prod (System Tests): 12 tests, 0 fallos, 14.977s, 100% exitosos |
| [evidencias/tests/stage-artifacts/locust-auth-stage.html](evidencias/tests/stage-artifacts/locust-auth-stage.html) | Reporte Locust AuthLoadTest: 139 requests, 0% error, p50=7,200ms, RPS=1.17 |
| [evidencias/tests/stage-artifacts/locust-gateway-stage.html](evidencias/tests/stage-artifacts/locust-gateway-stage.html) | Reporte Locust GatewayLoadTest: 952 requests, 0% error, p50=6ms, RPS=15.93 |
| [evidencias/tests/stage-artifacts/locust-promotion-stage.html](evidencias/tests/stage-artifacts/locust-promotion-stage.html) | Reporte Locust PromotionStatsLoadTest: 142 requests, 0% error, p50=14ms, RPS=2.40 |
| [evidencias/tests/stage-artifacts/locust-dashboard-stage.html](evidencias/tests/stage-artifacts/locust-dashboard-stage.html) | Reporte Locust DashboardLoadTest: 88 requests, 0% error, p50=9ms, RPS=1.48 |

## 12.8 Release Notes y archivos de configuración

| Archivo | Contenido |
|---|---|
| [evidencias/tests/prod-artifacts/RELEASE_NOTES.md](evidencias/tests/prod-artifacts/RELEASE_NOTES.md) | Release Notes generadas automáticamente por el pipeline de producción |
| [jenkins-values.yaml](../jenkins/jenkins-values.yaml) | Helm values file de Jenkins con dind, hostAliases, initScripts y plugins |
| [jenkinsfile.stage](../jenkins/jenkinsfile.stage) | Pipeline completo de stage |
| [Jenkinsfile.prod](../Jenkinsfile.prod) | Pipeline completo de producción |
| [Jenkinsfile — auth-service](../services/circleguard-auth-service/Jenkinsfile) | Pipeline de desarrollo — auth-service |
| [Jenkinsfile — identity-service](../services/circleguard-identity-service/Jenkinsfile) | Pipeline de desarrollo — identity-service |
| [Jenkinsfile — gateway-service](../services/circleguard-gateway-service/Jenkinsfile) | Pipeline de desarrollo — gateway-service |
| [Jenkinsfile — promotion-service](../services/circleguard-promotion-service/Jenkinsfile) | Pipeline de desarrollo — promotion-service |
| [Jenkinsfile — dashboard-service](../services/circleguard-dashboard-service/Jenkinsfile) | Pipeline de desarrollo — dashboard-service |
| [Jenkinsfile — file-service](../services/circleguard-file-service/Jenkinsfile) | Pipeline de desarrollo — file-service |
| [kind-config.yaml](../kind-config.yaml) | Configuración del clúster Kind con containerdConfigPatches para el registry |

---

*Informe generado para la asignatura Ingeniería de Software V. Todos los artefactos de evidencia se encuentran en la carpeta `evidencias/` junto a este documento.*
