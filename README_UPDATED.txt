SMARTPAYROLL LOCAL - UPDATED BUILD
=================================

WHAT CHANGED
- Added employee password change workflow (current password required; new password minimum 8 characters).
- Passwords continue to be salted and hashed with PBKDF2-HMAC-SHA256.
- Removed the MySQL password from Java source code. run.bat prompts for it each time.
- Kept the existing admin dashboard, employee management, payroll processing, report export and payslip print features.
- Included only runtime files, not the Connector/J source tree.

RUN
1. Ensure MySQL Server is running.
2. In MySQL Workbench, ensure database payroll_db and its employee and payroll tables exist. If they do not exist, inspect database/payroll.sql before running it; do not drop existing databases.
3. Open this folder in File Explorer and double-click run.bat, or in PowerShell run .\run.bat from this folder.
4. Enter your local MySQL root password when prompted.
5. Open http://localhost:8080 and keep the terminal open.

DEFAULT ADMIN LOGIN
Username: admin
Password: Admin@12345
The server creates this account only if an admin account does not already exist. If an admin row already exists, its password is not reset automatically.
Change this demo password before using the app with real data.

IMPORTANT
The original uploaded Java file contained a hard-coded database credential. This updated build no longer embeds it. If that credential was real, rotate/change it in MySQL. Do not upload database passwords or other secrets publicly.

This is a local educational demo, not a production-hardened payroll system. Use only test data until security and deployment are reviewed.
