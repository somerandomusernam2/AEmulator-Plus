# Firmware-tool port matrix

This build ports the extraction/conversion utilities from `New folder.zip` into the Android importer. Existing native importer paths are reused rather than duplicated.

| Supplied utility | Android implementation | Status |
|---|---|---|
| `combine_edl_system.py` | `FirmwareToolset.planEdl(...)` / `combineEdl(...)` + `Importer.importEdl` (picks the matching rawprogram XML automatically, merges split/sparse chunks into system.img; also boot) | Ported |
| `combine_sparse.py` | `FirmwareToolset.combineSparseParts(...)` | Ported; numeric chunk ordering |
| `extract_dat_br_ota.py` | existing Brotli + `TransferList` pipeline; archive-aware VDAT pairing | Ported/reused |
| `extract_tarmd5.py` | existing TAR/TAR.MD5 importer | Ported/reused |
| `extract_tot_partition.py` | `FirmwareToolset.Lg.extractTot(...)` | Ported |
| `extract_tot_partition(1).py` | same LG TOT implementation; duplicate utility intentionally not duplicated | Ported/reused |
| `lgbinextractor.py` | `FirmwareToolset.Lg.extractBin(...)` | Ported |
| `ofp_mtk_decrypt.py` | `FirmwareToolset.Ofp` MTK key probing, table decryption and payload extraction | Ported |
| `ofp_qc_decrypt.py` | `FirmwareToolset.Ofp` Qualcomm key probing/XML extraction/page handling plus encrypted ZIP support | Ported |
| `opscrypto.py` | `FirmwareToolset.Ops` mbox/SBOX/key-update/custom crypto and payload extraction | Ported |
| `ops_decrypt_frida.py` | Not an offline extractor; it is runtime Frida instrumentation. Its extraction logic is represented by the `opscrypto.py` port. | Intentionally not embedded |
| `pac_extractor.py` | `FirmwareToolset.Pac.extract(...)` | Ported |
| `pkg_extract.py` | `FirmwareToolset.Pkg` recursive wrapper handling, OEM wrappers, ZIP/compression/filesystem dispatch, payload/super support | Ported/reused |
| `rfs2zip.py` | `FirmwareToolset.Rfs.toZip(...)` (system/factoryfs/factoryfs.img/factoryfs.rfs are treated as the system partition) | Ported |
| `sbf_extract.py` | `FirmwareToolset.Sbf.extract(...)` | Ported |
| `sdat2img.py` | existing `TransferList` block-image reconstruction | Ported/reused |
| `sparse_img_converter.py` | existing `SparseSource` + `FirmwareToolset.combineSparseParts(...)` | Ported/reused |
| `squashfs2zip.py` | `FirmwareToolset.SquashFs` reader, including zlib/LZMA/LZO/XZ/LZ4/Zstd compression IDs | Ported |
| `unnbh.py` | `FirmwareToolset.Nbh.extract(...)` | Ported |
| `vdat2img.py` | `FirmwareToolset.Vdat` + archive-aware pair handling | Ported |
| `yaffs2zip.py` | existing `Yaffs2Reader` importer | Ported/reused |

## Integration behavior

Specialized firmware files discovered inside ZIP, 7z, RAR and TAR containers are staged and routed through the same importer. `system.new.dat`/`.br` plus `system.transfer.list` are paired even when they occur inside a 7z or RAR rather than as filesystem siblings.

Generated filesystem archives (RFS/SquashFS) and reconstructed images are fed back into the existing Android image importer, so their contents become part of the normal guest tree rather than being left as unprocessed temporary files.

`ops_decrypt_frida.py` is deliberately not packaged as a Frida runtime. It is an instrumentation script for a running process, not a static firmware-extraction format. The actual OPS encryption/decryption algorithm needed for offline extraction comes from `opscrypto.py` and is what is integrated into the app.

## Verification

The Android build was source-checked with Java 17 and the standalone `TransferList` Kotlin source was compiled and exercised against a deterministic synthetic transfer-list fixture. Full Gradle/Android APK compilation could not be performed in this environment because the Gradle 8.14.2 distribution is not locally cached and the environment has no network access.
