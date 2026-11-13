package com.mistbell.tavern.v3.feature.era

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EraGameZipInstallerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun updateKeepsSavesAndReplacesGameFiles() {
        val installer = EraGameZipInstaller(temporary.newFolder("games"))
        val first =
            installer.install(
                zip(
                    "first.zip",
                    mapOf(
                        "game/main.erb" to "version-one",
                        "sav/save1.sav" to "sav-one",
                        "save9.sav" to "root-save",
                    ),
                ),
                "eraTW",
            )

        val second =
            installer.install(
                zip("second.zip", mapOf("game/main.erb" to "version-two")),
                "eraTW",
            )

        assertEquals(first.directory.canonicalFile, second.directory.canonicalFile)
        assertEquals("version-two", File(second.directory, "game/main.erb").readText())
        assertEquals("sav-one", File(second.directory, "sav/save1.sav").readText())
        assertEquals("root-save", File(second.directory, "save9.sav").readText())
    }

    @Test
    fun rejectsPathTraversalWithoutWritingOutsideTheGameDirectory() {
        val games = temporary.newFolder("games")
        val escaped = File(games.parentFile, "escaped.txt")
        val installer = EraGameZipInstaller(games)

        assertThrows(IOException::class.java) {
            installer.install(zip("evil.zip", mapOf("../escaped.txt" to "nope")), "eraTW")
        }

        assertFalse(escaped.exists())
    }

    @Test
    fun rejectsAnEmptyZip() {
        val installer = EraGameZipInstaller(temporary.newFolder("games"))

        assertThrows(IllegalArgumentException::class.java) {
            installer.install(zip("empty.zip", emptyMap()), "eraTW")
        }
    }

    @Test
    fun keepsChineseEntryNamesAndStableDirectoryName() {
        val installer = EraGameZipInstaller(temporary.newFolder("games"))
        val install =
            installer.install(
                zip("eraTW.zip", mapOf("脚本/主.erb" to "内容")),
                "eraTW 4.0 蛇版",
            )

        assertEquals("eraTW 4.0 蛇版", install.name)
        assertTrue(File(install.directory, "脚本/主.erb").isFile)
        assertEquals("内容", File(install.directory, "脚本/主.erb").readText())
    }

    @Test
    fun unwrapsASingleTopLevelGameFolder() {
        val installer = EraGameZipInstaller(temporary.newFolder("games"))
        val install =
            installer.install(
                zip(
                    "wrapped.zip",
                    mapOf(
                        "eraTW/erb/main.erb" to "wrapped",
                        "eraTW/csv/Chara.csv" to "csv",
                    ),
                ),
                "eraTW",
            )

        assertEquals("wrapped", File(install.directory, "erb/main.erb").readText())
        assertEquals("csv", File(install.directory, "csv/Chara.csv").readText())
        assertFalse(File(install.directory, "eraTW").exists())
    }

    private fun zip(
        name: String,
        entries: Map<String, String>,
    ): File {
        val file = temporary.newFile(name)
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            entries.forEach { (path, content) ->
                output.putNextEntry(ZipEntry(path))
                output.write(content.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        return file
    }
}
