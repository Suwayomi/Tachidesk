package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import org.intellij.lang.annotations.Language
import suwayomi.tachidesk.global.impl.util.Bcrypt

@Suppress("ClassName", "unused")
class M0043_AddUsers : SQLMigration() {
    class UserSql {
        private val password = Bcrypt.encryptPassword("password")

        @Language("SQL")
        val sql =
            """
            CREATE TABLE USER
            (
                ID INT AUTO_INCREMENT PRIMARY KEY,
                USERNAME VARCHAR(64) NOT NULL,
                PASSWORD VARCHAR(90) NOT NULL
            );
            
            INSERT INTO USER(USERNAME, PASSWORD)
            SELECT 'admin','$password';
            
            CREATE TABLE USERROLES
            (
                USER INT NOT NULL,
                ROLE VARCHAR(24) NOT NULL,
                CONSTRAINT FK_USERROLES_USER_ID
                    FOREIGN KEY (USER) REFERENCES USER (ID) ON DELETE CASCADE
            );
            
            INSERT INTO USERROLES(USER, ROLE)
            SELECT 1, 'ADMIN';

            CREATE TABLE USERPERMISSIONS
            (
                USER INT NOT NULL,
                PERMISSION VARCHAR(128) NOT NULL,
                CONSTRAINT FK_USERPERMISSIONS_USER_ID
                    FOREIGN KEY (USER) REFERENCES USER (ID) ON DELETE CASCADE
            );
            
            -- Step 1: Add USER column to tables CATEGORY, MANGAMETA, CHAPTERMETA, CATEGORYMANGA, GLOBALMETA, and CATEGORYMETA
            ALTER TABLE CATEGORY ADD COLUMN USER INT NOT NULL DEFAULT 1;
            ALTER TABLE TRACKRECORD ADD COLUMN USER INT NOT NULL DEFAULT 1;
            ALTER TABLE MANGAMETA ADD COLUMN USER INT NOT NULL DEFAULT 1;
            ALTER TABLE CHAPTERMETA ADD COLUMN USER INT NOT NULL DEFAULT 1;
            ALTER TABLE CATEGORYMANGA ADD COLUMN USER INT NOT NULL DEFAULT 1;
            ALTER TABLE GLOBALMETA ADD COLUMN USER INT NOT NULL DEFAULT 1;
            ALTER TABLE CATEGORYMETA ADD COLUMN USER INT NOT NULL DEFAULT 1;
            ALTER TABLE SOURCEMETA ADD COLUMN USER INT NOT NULL DEFAULT 1;

            -- Add foreign key constraints to reference USER table
            ALTER TABLE CATEGORY ADD CONSTRAINT FK_CATEGORY_USER FOREIGN KEY (USER) REFERENCES USER(ID) ON DELETE CASCADE;
            ALTER TABLE MANGAMETA ADD CONSTRAINT FK_MANGAMETA_USER FOREIGN KEY (USER) REFERENCES USER(ID) ON DELETE CASCADE;
            ALTER TABLE CHAPTERMETA ADD CONSTRAINT FK_CHAPTERMETA_USER FOREIGN KEY (USER) REFERENCES USER(ID) ON DELETE CASCADE;
            ALTER TABLE CATEGORYMANGA ADD CONSTRAINT FK_CATEGORYMANGA_USER FOREIGN KEY (USER) REFERENCES USER(ID) ON DELETE CASCADE;
            ALTER TABLE GLOBALMETA ADD CONSTRAINT FK_GLOBALMETA_USER FOREIGN KEY (USER) REFERENCES USER(ID) ON DELETE CASCADE;
            ALTER TABLE CATEGORYMETA ADD CONSTRAINT FK_CATEGORYMETA_USER FOREIGN KEY (USER) REFERENCES USER(ID) ON DELETE CASCADE;
            ALTER TABLE SOURCEMETA ADD CONSTRAINT FK_CATEGORYMETA_USER FOREIGN KEY (USER) REFERENCES USER(ID) ON DELETE CASCADE;
            
            ALTER TABLE CATEGORY
            ALTER COLUMN USER DROP DEFAULT;
            
            ALTER TABLE TRACKRECORD
            ALTER COLUMN USER DROP DEFAULT;
            
            ALTER TABLE MANGAMETA
            ALTER COLUMN USER DROP DEFAULT;
            
            ALTER TABLE CHAPTERMETA
            ALTER COLUMN USER DROP DEFAULT;
            
            ALTER TABLE CATEGORYMANGA
            ALTER COLUMN USER DROP DEFAULT;
            
            ALTER TABLE GLOBALMETA
            ALTER COLUMN USER DROP DEFAULT;
            
            ALTER TABLE CATEGORYMETA
            ALTER COLUMN USER DROP DEFAULT;
            
            ALTER TABLE SOURCEMETA
            ALTER COLUMN USER DROP DEFAULT;
            
            -- Step 2: Create the CHAPTERUSER table
            CREATE TABLE CHAPTERUSER
            (
                ID             INT AUTO_INCREMENT PRIMARY KEY,
                LAST_READ_AT   BIGINT DEFAULT 0 NOT NULL,
                LAST_PAGE_READ INT DEFAULT 0 NOT NULL,
                BOOKMARK       BOOLEAN DEFAULT FALSE NOT NULL,
                READ           BOOLEAN DEFAULT FALSE NOT NULL,
                CHAPTER        INT NOT NULL,
                USER           INT NOT NULL,
                CONSTRAINT FK_CHAPTERUSER_CHAPTER_ID
                    FOREIGN KEY (CHAPTER) REFERENCES CHAPTER (ID) ON DELETE CASCADE,
                CONSTRAINT FK_CHAPTERUSER_USER_ID
                    FOREIGN KEY (USER) REFERENCES USER (ID) ON DELETE CASCADE
            );
            
            -- Step 3: Create the MANGAUSER table
            CREATE TABLE MANGAUSER
            (
                ID          INT AUTO_INCREMENT PRIMARY KEY,
                IN_LIBRARY  BOOLEAN DEFAULT FALSE NOT NULL,
                IN_LIBRARY_AT BIGINT DEFAULT 0 NOT NULL,
                MANGA       INT NOT NULL,
                USER        INT NOT NULL,
                CONSTRAINT FK_MANGAUSER_MANGA_ID
                    FOREIGN KEY (MANGA) REFERENCES MANGA (ID) ON DELETE CASCADE,
                CONSTRAINT FK_MANGAUSER_USER_ID
                    FOREIGN KEY (USER) REFERENCES USER (ID) ON DELETE CASCADE
            );
            
            -- Step 4: Backfill the CHAPTERUSER and MANGAUSER tables with existing data
            INSERT INTO CHAPTERUSER (LAST_READ_AT, LAST_PAGE_READ, BOOKMARK, READ, CHAPTER, USER)
            SELECT LAST_READ_AT, LAST_PAGE_READ, BOOKMARK, READ, ID AS CHAPTER, 1 AS USER
            FROM CHAPTER;
            
            INSERT INTO MANGAUSER (IN_LIBRARY, IN_LIBRARY_AT, MANGA, USER)
            SELECT IN_LIBRARY, IN_LIBRARY_AT, ID AS MANGA, 1 AS USER
            FROM MANGA;
            
            -- Step 5: Remove extracted columns from CHAPTER and MANGA tables
            ALTER TABLE CHAPTER
            DROP COLUMN LAST_READ_AT;
            ALTER TABLE CHAPTER
            DROP COLUMN LAST_PAGE_READ;
            ALTER TABLE CHAPTER
            DROP COLUMN BOOKMARK;
            ALTER TABLE CHAPTER
            DROP COLUMN READ;
            
            ALTER TABLE MANGA
            DROP COLUMN IN_LIBRARY;
            ALTER TABLE MANGA
            DROP COLUMN IN_LIBRARY_AT;
            """.trimIndent()
    }

    override val sql by lazy {
        UserSql().sql
    }
}
