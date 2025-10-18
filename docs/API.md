File Collection Backend API

Projects
- POST `/api/projects`
  - Body (JSON):
    - `name` string
    - `allowedFileTypes` array[string] (extensions, e.g. ["pdf","jpg"]) optional
    - `fileSizeLimitBytes` number optional
    - `expectedUserFields` array[object] optional (e.g. [{"key":"name","label":"姓名","required":true}])
    - `startAt` string (ISO datetime) optional
    - `endAt` string (ISO datetime) optional
    - `allowResubmit` boolean
  - Returns: project with parsed fields

- GET `/api/projects/{id}` -> same shape as POST response
- GET `/api/projects` -> list of Project entities

Submissions
- POST `/api/projects/{id}/submissions` (multipart/form-data)
  - Parts:
    - `submitter`: JSON string with user fields (e.g. {"name":"张三","studentNo":"1001"})
    - `files`: one or more files
  - Validations:
    - Time window (startAt/endAt)
    - File extension in `allowedFileTypes` if present
    - Each file size <= `fileSizeLimitBytes` if present
    - Duplicates rejected if project `allowResubmit` is false (per submitter fingerprint)
  - Returns JSON: id, submitter, fileUrls[], submitCount, expired, ipAddress, userAgent, osName, osVersion, browserName, browserVersion, deviceType, ipCountry, ipProvince, ipCity, createdAt, updatedAt

- GET `/api/projects/{id}/submissions`
  - Query: `page` (default 0), `size` (default 20)
  - Returns: Spring Page JSON with `content`, `totalElements`, `totalPages`, etc.

- GET `/api/projects/{id}/submissions/export`
  - Returns: CSV file (text/csv) with columns: id, submitter(json), fileUrls(json), submitCount, expired, ipAddress, userAgent, osName, osVersion, browserName, browserVersion, deviceType, ipCountry, ipProvince, ipCity, createdAt, updatedAt

Notes
- Files are uploaded to Aliyun OSS under `prefix/yyyy/MM/dd/md5.ext`.
- Submitter uniqueness is determined by an MD5 fingerprint of the canonicalized submitter JSON (sorted keys).
- `projects.totalSubmitters` is updated after each submission using distinct submitter fingerprints for that project.
- Retention: keep up to 20 submissions per submitter per project. Older ones are marked `expired=true` and their files are deleted from OSS.

UA & GeoIP
- User-Agent is parsed into structured fields (OS, Browser, DeviceType) using embedded parser heuristics.
- GeoIP lookup is optional via MaxMind City MMDB. Enable by setting `geoip.enabled=true` and `geoip.mmdbPath` to the db file path.

Project Update/Offline
- PUT `/api/projects/{id}`
  - Body (JSON): any subset of fields from project create, plus `offline` boolean
  - When `offline=true`, new submissions are rejected with message "Project is offline".

Admin Auth & Management
- POST `/api/admin/auth/login` { username, password } -> set session, returns { username }
- GET `/api/admin/auth/me` -> current admin username (if logged in)
- GET `/api/admin/users` [SUPER only] -> list admin users
- POST `/api/admin/users` [SUPER only] -> create admin user { username, password, role: SUPER|ADMIN }
- POST `/api/admin/users/{userId}/projects/{projectId}` [SUPER only] -> grant project permission
- DELETE `/api/admin/users/{userId}/projects/{projectId}` [SUPER only] -> revoke project permission

Security Rules
- Public:
  - GET `/api/projects`, GET `/api/projects/{id}`
  - POST `/api/projects/{id}/submissions`
- Admin-only with permission:
  - GET `/api/projects/{id}/submissions` (paged)
  - GET `/api/projects/{id}/submissions/export`
  - PUT `/api/projects/{id}` (update/offline)
- Super admin only:
  - POST `/api/projects` (create project)
  - All admin user/permission management endpoints

Super Admin Seeding
- On startup, if no admin `ADMIN_INIT_USERNAME` exists, a SUPER user is created with:
  - username: `${ADMIN_INIT_USERNAME:admin}`
  - password: `${ADMIN_INIT_PASSWORD:admin123}` (BCrypt hashed at rest)
