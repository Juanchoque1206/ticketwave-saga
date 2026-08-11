# TicketWave Events (API service)

Plataforma monolítica modular (Spring Boot 4, Java 21) para gestión de eventos y venta de tickets con un flujo unificado de **reserva + compra** basado en `TicketOrder`. El flujo de compra es orquestado por un **saga** que corre en un servicio separado ([`ticketwave-orchestrator`](../ticketwave-orchestrator)); este módulo publica eventos de dominio y ejecuta los comandos que el saga envía.

## Tecnologías

- **Java 21** · **Spring Boot 4** · Maven 3.9+
- **Spring Data JPA** + PostgreSQL (H2 embebida para desarrollo local)
- **Spring Security + JWT** (jjwt 0.12)
- **Redis** (bloqueo de tickets y detección de fraude)
- **RabbitMQ / AMQP** (bus de eventos y comandos; consume `ticketwave.commands` y publica en `ticketwave.events`)
- **Arquitectura hexagonal**: `domain` (entidades y lógica), `application` (casos de uso), `infrastructure` (adaptadores)
- **Contratos de dominio propios**: puertos de bus, eventos, comandos y estado del saga viven en este módulo (idénticos al orquestador para interoperar por RabbitMQ)
- **OpenAPI / Swagger UI** · **Lombok**

## Requerimientos

- JDK 21 y Maven 3.9+
- Perfil `local`: nada extra (H2 en memoria, bus en memoria, seed)
- Perfil `rabbitmq`: PostgreSQL 15+, Redis 7+ y RabbitMQ 3.x (+ el orquestador para el flujo completo)

## Docker (dependencias externas)

Contenedores independientes (puedes arrancarlos por separado o juntos):

```bash
# rabbitmq
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:4-management

# redis
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

Panel de gestión de RabbitMQ: <http://localhost:15672/#/queues> (usuario/contraseña: `guest`/`guest`). Ahí puedes inspeccionar los exchanges (`ticketwave.events`, `ticketwave.commands`), las colas (`ticketwave.events.all`, `ticketwave.commands.all`) y los mensajes publicados.

## Perfiles

| Perfil      | Base de datos | Bus                       | Seeder | Uso                  |
|-------------|---------------|---------------------------|--------|----------------------|
| `local`     | H2 en memoria | En memoria (síncrono)     | Sí     | Desarrollo por defecto |
| `test`      | H2 en memoria | En memoria (síncrono)     | No     | `mvn test`           |
| `rabbitmq`  | PostgreSQL    | RabbitMQ (cola propia)    | No     | Producción (con orquestador) |

En producción este servicio consume los comandos del saga (`ProcessPaymentCommand`, `IssueTicketCommand`, `CancelTicketOrderCommand`, `RefundPaymentCommand`, `NotifyOrderCommand`) desde su cola `ticketwave.commands.all.api` y publica los eventos resultantes en `ticketwave.events`.

## Estructura

```
src/main/java/com/ticketwave/
 ├── TicketwaveApplication.java     # @SpringBootApplication + @EnableCaching/@EnableScheduling/@EnableMethodSecurity/@EnableRabbit
 ├── domain/                        # Entidades, enums y repositorios
 │   ├── event|order|payment|ticket|promotion|user|venue|notification/
 ├── application/                   # Casos de uso y servicios (Auth, Event, TicketOrder, Fraud, Payment, ConfirmOrderUseCase...)
 ├── infrastructure/                # Adaptadores (controllers, repos JPA, RabbitMQ, Redis, seguridad, DTOs)
 │   ├── controller/                #   API REST
 │   ├── repository/                #   Jpa*Repository (Spring Data)
 │   ├── bus/                       #   RabbitMQ adapters (event bus y command bus), InMemoryEventBus...
 │   ├── security/                  #   JWT + UserDetailsService
 │   ├── payment|notification|venue/ # Subscriptores de eventos de dominio
 │   └── util/                      #   QrCodeGenerator
 └── config/                        # Security, JWT filter, OpenAPI, EventBusConfig, DataSeeder, cache
```

Los contratos del bus y el estado del saga viven en este mismo módulo (`com.ticketwave.domain.bus/commands/events/saga`), con los mismos paquetes que el orquestador para que los mensajes JSON sean interoperables.

## Ejecución

```bash
# Desarrollo local (por defecto): H2 en memoria + bus en memoria + seed
mvn spring-boot:run

# Producción (PostgreSQL + Redis + RabbitMQ); requiere el orquestador corriendo
DB_URL=jdbc:postgresql://localhost:5432/ticketwave-sa \
DB_USERNAME=postgres DB_PASSWORD=postgres \
REDIS_HOST=localhost \
JWT_SECRET=<secreto-de-32-bytes> \
mvn spring-boot:run -Dspring-boot.run.profiles=rabbitmq
```

Swagger UI: http://localhost:8081/swagger-ui.html · Actuator: http://localhost:8081/actuator/health

> Para el flujo de compra completo (`reserva → pago → ticket → notificación`) ejecuta también el orquestador y la infraestructura (ver [`README.md` principal](../README.md)).

### Variables de entorno

| Variable          | Por defecto                           | Descripción                  |
|-------------------|---------------------------------------|------------------------------|
| `DB_URL`          | `jdbc:postgresql://localhost:5432/ticketwave-sa` | URL JDBC PostgreSQL |
| `DB_USERNAME`     | `postgres`                            | Usuario de la BD             |
| `DB_PASSWORD`     | `postgres`                            | Contraseña de la BD          |
| `REDIS_HOST/PORT` | `localhost` / `6379`                  | Redis (fraude y bloqueo)     |
| `JWT_SECRET`      | valor de desarrollo                   | Clave HMAC (≥32 bytes)       |
| `JWT_EXPIRATION`  | `86400000`                            | Expiración del token (ms)    |
| `ORDER_TTL_MINUTES` | `15`                                 | Vigencia de reservas PENDING |
| `TICKETWAVE_EVENT_QUEUE` / `TICKETWAVE_COMMAND_QUEUE` | `ticketwave.events.all.api` / `ticketwave.commands.all.api` | Colas de bus |
| `MAIL_HOST/PORT`  | `smtp.gmail.com` / `587`              | SMTP para notificaciones     |

## Credenciales de demostración (seed automático, perfil `local`)

| Usuario | Contraseña | Rol   |
|---------|------------|-------|
| admin   | admin1234  | ADMIN |
| user    | user1234   | USER  |

## Endpoints principales

### Autenticación y usuarios
| Método | Ruta                     | Acceso  | Descripción                 |
|--------|--------------------------|---------|-----------------------------|
| POST   | `/api/users/register`    | Público | Registro → JWT              |
| POST   | `/api/users/login`       | Público | Login → JWT                 |
| GET    | `/api/users/me`          | Auth    | Perfil del usuario          |
| GET    | `/api/users`             | ADMIN   | Listar usuarios             |
| GET    | `/api/users/{id}`        | ADMIN   | Detalle de usuario          |

### Eventos
| Método | Ruta                  | Acceso  | Descripción                                |
|--------|-----------------------|---------|--------------------------------------------|
| GET    | `/api/events`         | Público | Búsqueda paginada (city, artist, venue, rango de fechas) |
| GET    | `/api/events/{id}`    | Público | Detalle                                    |
| POST   | `/api/events`         | ADMIN   | Crear evento                               |
| PUT    | `/api/events/{id}`    | ADMIN   | Actualizar evento                          |
| DELETE | `/api/events/{id}`    | ADMIN   | Cancelar evento                            |

### Órdenes y pagos
| Método | Ruta                             | Acceso  | Descripción                          |
|--------|----------------------------------|---------|--------------------------------------|
| POST   | `/api/orders`                    | Auth    | Reservar tickets (publica `TicketOrderCreated`) |
| GET    | `/api/orders`                    | Auth    | Órdenes del usuario                  |
| GET    | `/api/orders/{orderId}`          | Auth    | Detalle de orden                     |
| POST   | `/api/orders/{orderId}/cancel`   | Auth    | Cancelar reserva antes del pago      |
| POST   | `/api/payments`                  | Auth    | Confirmar orden con pago             |
| GET    | `/api/payments/order/{orderId}`  | Auth    | Pago de una orden                    |

### Tickets
| Método | Ruta                          | Acceso  | Descripción                       |
|--------|-------------------------------|---------|-----------------------------------|
| GET    | `/api/tickets/{id}`           | Auth    | Detalle del ticket (QR)           |
| GET    | `/api/tickets/order/{orderId}`| Auth    | Tickets de una orden              |
| POST   | `/api/tickets/validate`       | ADMIN   | Validar ticket en el venue        |
| POST   | `/api/tickets/{id}/refund`    | Auth    | Reembolsar ticket                 |

### Promociones, fraude y notificaciones
| Método | Ruta                        | Acceso  | Descripción                        |
|--------|-----------------------------|---------|------------------------------------|
| POST   | `/api/promotions`           | ADMIN   | Crear código de descuento          |
| GET    | `/api/promotions`           | Público | Promociones activas                |
| GET    | `/api/fraud/check`          | Auth    | Evaluación de riesgo de fraude     |
| GET    | `/api/notifications`        | Auth    | Notificaciones del usuario         |
| PATCH  | `/api/notifications/{id}/read` | Auth | Marcar notificación como leída    |

## Integración con el saga

Este servicio participa en el saga de compra a través del bus:

1. `POST /api/orders` → reserva capacidad y publica `TicketOrderCreated` en `ticketwave.events`.
2. El orquestador responde enviando `ProcessPaymentCommand` → `ConfirmOrderUseCase` procesa el pago y publica `PaymentAuthorized`/`PaymentFailed`.
3. `IssueTicketCommand` → `IssueTicketUseCase` emite los tickets con QR y publica `TicketIssued`/`TicketDeliveryFailed`.
4. `NotifyOrderCommand` → `NotificationEventSubscriber` envía el email y publica `NotificationSent`/`NotificationFailed`.
5. Compensaciones: `CancelTicketOrderCommand` cancela la orden y libera capacidad; `RefundPaymentCommand` reembolsa el pago.

Las órdenes `PENDING` expiran y liberan capacidad automáticamente con `OrderExpiryJob` (cron configurable `ticketwave.order-expiry-cron`).

El orquestador (`ticketwave-orchestrator`) mantiene el snapshot del saga en Redis y reanuda flujos interrumpidos: ver `../README.md`.

## Pruebas

```bash
mvn test
```

Las pruebas usan el perfil `test` (H2 en memoria + buses en memoria). Ejemplo: `TicketOrderFlowIntegrationTest` cubre el flujo reserva → confirmación → emisión.

## Seguridad

- JWT bearer (HMAC, jjwt 0.12) emitido en `/api/users/login` y `/api/users/register`; sesiones `STATELESS`.
- Rutas públicas: register/login, `/api/events/**`, Swagger/OpenAPI, consola H2 y `/actuator/health`. El resto requiere autenticación.
- Endpoints administrativos protegidos con `@PreAuthorize("hasRole('ADMIN')")`.
- Detección de fraude: límite de intentos por usuario/IP en Redis y prevención de órdenes duplicadas.
- Contraseñas con `BCryptPasswordEncoder`.