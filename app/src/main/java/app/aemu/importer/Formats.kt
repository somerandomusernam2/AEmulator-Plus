package app.aemu.importer

import java.io.InputStream

/*
 * AEmulator 0.0.0.3 Formats.kt, reduced for the Plus merge.
 *
 * 0.0.0.3 kept its Sony SIN, PAC, YAFFS2, FAT, super.img, payload.bin and EROFS readers in this file. The fork has its own
 * implementations of all of them (SonySin, Yaffs2Reader, FatReader, Simg2Img, BlockOta, ErofsReader, ExtentSource.kt...),
 * and the Importer pipeline uses those, so the 0.0.0.3 copies were dropped: they redeclared ExtentSource and ErofsReader.
 * Only TreeSource is kept: ErofsImage (0.0.0.3's EROFS reader, used by ApexUnpacker) implements it.
 */

/** A file tree of a partition image, delivered object by object (directories before their contents). */
interface TreeSource {
    /** [link] is set for symlinks; [data] opens the contents of a regular file */
    fun walk(visit: (path: String, isDir: Boolean, link: String?, mode: Int, size: Long, data: (() -> InputStream)?) -> Unit)
}
