
# Java Database Backup Utility

This repository contains a **database backup utility** written in **Java** for **PostgreSQL** and **MySQL** databases.

## Overview

The utility allows you to create backups of your PostgreSQL and MySQL databases quickly and reliably.  
It is designed to be **cross-platform**, running on any system with a Java Runtime Environment (JRE) installed.

## Features

- Supports **PostgreSQL** and **MySQL**
- Works on **Windows**, **Linux**, and **macOS**
- Simple configuration for database credentials and backup paths
- Generates timestamped backup files for easy management
- Lightweight — no heavy frameworks required

## Requirements

- **Java 8** or higher
- PostgreSQL or MySQL client tools installed and available in system PATH
- Access credentials for the target database(s)

## Usage

1. Clone the repository:
   ```bash
   git clone https://github.com/innaesim/backup_utility.git
   ```

2. Compile the Java source files:

   ```bash
   javac -d out src/**/*.java
   ```

3. Run the utility:

   ```bash
   java -cp out com.example.BackupUtility
   ```

4. Configure database connection settings in the provided configuration file or within the program.

## Example Backup Output

* PostgreSQL: `backup_postgres_2025-08-12_1430.sql`
* MySQL: `backup_mysql_2025-08-12_1430.sql`

## License

This project is licensed under the MIT License

---

© 2025 Duncan Johanne Kachasu. All rights reserved.
