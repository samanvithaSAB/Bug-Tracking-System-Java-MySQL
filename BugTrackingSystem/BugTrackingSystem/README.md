# Bug Tracking System & Project Management

A Java 17 + Swing desktop application using JDBC and MySQL 8.

## Requirements
- JDK 17 or newer
- MySQL Server 8.x running locally
- VS Code with Extension Pack for Java and Maven support
- Internet connection on first Maven build (to download MySQL Connector/J)

## Configure MySQL
1. Start MySQL Server.
2. Open `src/main/java/com/bugtracker/BugTrackingApp.java`.
3. Set `DB_USER` and `DB_PASSWORD` defaults to your MySQL username/password, OR set environment variables:
   - `BUGTRACKER_DB_USER`
   - `BUGTRACKER_DB_PASSWORD`
   - Optional `BUGTRACKER_DB_URL`
4. The app creates the `bug_tracking_system` database and its tables automatically on first run.
   You can also execute `sql/schema.sql` manually. The MySQL account must have permission to create a database.

## Run in VS Code
1. Open the **BugTrackingSystem** folder (the folder containing `pom.xml`) in VS Code.
2. Wait for Maven dependencies to finish downloading.
3. Open `src/main/java/com/bugtracker/BugTrackingApp.java`.
4. Run the `main` method, or open the VS Code terminal and execute:
   ```bash
   mvn clean compile exec:java
   ```

## Demo logins
| Role | Username | Password |
|---|---|---|
| Administrator | admin | admin123 |
| Tester | tester | tester123 |
| Developer | developer | dev123 |

The accounts are inserted automatically if they do not already exist. These are classroom-demo credentials, not production security credentials.

## Suggested demonstration sequence
1. Login as `admin`; create a project and add a developer/tester if needed.
2. Login as `tester`; report a bug.
3. Login as `admin`; assign the bug to `developer`.
4. Login as `developer`; update its status and add a comment.
5. Open Reports and demonstrate project-wise, developer-wise and status-wise analytics.
6. Open Activity Log to show the audit trail.
7. Demonstrate keyword/status/priority filtering, sorting and pagination in Bugs.

## Advanced SQL mapping
- JOIN: bug list combines bugs, projects, reporter and developer.
- Aggregates: dashboard COUNT and reports using COUNT, SUM, AVG, MIN and MAX.
- GROUP BY/HAVING: project, developer and status reports.
- Search/filter: parameterized multi-condition query.
- Subquery: highest unresolved workload report.
- Transactions: assignment, status update and comment + activity record use commit.
- Sorting/pagination: whitelisted ORDER BY choices plus LIMIT/OFFSET.
- Role restrictions: GUI actions and role-scoped bug listing.
- Activity logging: bug report, assignment, status and comment actions.
- Report generation: project, developer, status and unresolved-bug reports.

## Important academic / security note
This is a functional academic starter implementation. Passwords are stored as plain text for simplicity in a local lab demonstration; production systems must use salted password hashing, stronger validation, prepared role permissions, and secure configuration. For the strongest demo, expand the UI with edit forms and more detailed exception handling after confirming your instructor's exact expectations.
