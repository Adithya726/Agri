# AgriSense

Adaptive agricultural decision support: farm records and farm state, crop / yield / disease
predictions, risk analysis, a decision engine, action plans with monitoring and replanning,
history, what-if analysis and a rule-based farm assistant. UI in English, Hindi and Telugu.

| Part | Folder | Port |
|------|--------|------|
| Frontend (React 19 + Vite) | `frontend/` | 5173 |
| Backend (Spring Boot 4, Java 21) | `backend/agrisense-backend/` | 8081 |
| Crop recommendation service (Flask) | `ml/crop-recommendation/` | 5000 |
| Disease detection service (Flask, PyTorch) | `ml/disease-detection/` | 5001 |
| Yield prediction service (Flask) | `ml/yield-prediction/` | 5002 |

Model files are stored with Git LFS. Run `git lfs pull` after cloning.

## Requirements

- Java 21 and Maven
- Node.js 20+ and npm
- Python 3.10+
- MySQL 8 (or MariaDB) on `localhost:3306`

## 1. Database

Create the schema. Hibernate creates the tables on first start (`ddl-auto=update`).

```sql
CREATE DATABASE capstone_project;
```

The connection is set in `backend/agrisense-backend/src/main/resources/application.properties`
(`root` / `root` by default). The standard Spring variables override it:
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

On MariaDB, also set `SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT=org.hibernate.dialect.MySQLDialect`.

## 2. ML services

Each service is a small Flask app. Start all three before using the prediction pages.

```bash
pip install flask flask-cors pandas scikit-learn joblib   # crop + yield
pip install torch torchvision pillow                     # disease (CPU build is enough)

cd ml/crop-recommendation && python app.py   # :5000
cd ml/disease-detection   && python app.py   # :5001
cd ml/yield-prediction    && python app.py   # :5002
```

## 3. Backend

```bash
cd backend/agrisense-backend
mvn package            # builds and runs the unit tests
java -jar target/agrisense-backend-0.0.1-SNAPSHOT.jar
```

Environment variables (all optional for local use):

| Variable | Default | Purpose |
|----------|---------|---------|
| `AGRISENSE_JWT_SECRET` | local dev secret | JWT signing key, at least 32 bytes. Set it outside local dev. |
| `AGRISENSE_JWT_EXPIRATION_MINUTES` | `480` | Login session length |
| `AGRISENSE_CORS_ORIGINS` | `http://localhost:5173` | Allowed frontend origins (comma separated) |
| `DISEASE_SERVICE_URL` | `http://localhost:5001/predict` | Disease detection service |
| `YIELD_SERVICE_URL` | `http://localhost:5002/predict` | Yield prediction service |

The crop recommendation service is called at `http://localhost:5000`.

## 4. Frontend

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

`frontend/.env` sets `VITE_API_BASE_URL=http://localhost:8081`. Open the app on
`http://localhost:5173` (not `127.0.0.1`), which is the origin the backend allows.

Other scripts: `npm run build`, `npm run lint` (oxlint).

## Accounts and roles

There are no seeded users. Register from the app (`/register`); new accounts get the `FARMER` role.

Adding agricultural actions to the shared knowledge base is limited to `ADMIN`. To make an
account an admin in a local database:

```sql
UPDATE farmers SET role = 'ADMIN' WHERE email = 'you@example.com';
```

For local testing we use two accounts created this way:

| Email | Role | Notes |
|-------|------|-------|
| `test.farmer@example.com` | ADMIN | owns one farm (Rice, Chittoor) with a saved farm state |
| `new.farmer@example.com` | FARMER | no farms, used to check empty states |

## Typical flow

Register → My Farms (add a farm) → Farm State (save the current conditions) → Predictions
(crop, yield, disease image, risk) → Decision Engine → Action Plan (generate, accept) →
Monitoring (report actions, replanning after the farm state changes) → History, What-If, Assistant.
