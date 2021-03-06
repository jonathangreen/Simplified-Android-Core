package org.nypl.simplified.tests.local.migration.from3master

import org.nypl.simplified.tests.migration.from3master.MigrationFrom3MasterContract
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class MigrationFrom3MasterTest : MigrationFrom3MasterContract() {

  override val logger: Logger
    get() = LoggerFactory.getLogger(MigrationFrom3MasterTest::class.java)
}
