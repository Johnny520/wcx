package x;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.android.dvm.wrapper.DvmBoolean;
import com.github.unidbg.linux.android.dvm.wrapper.DvmInteger;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class DecryptorHarness extends AbstractJni {

    private final String currentCore;
    private final String rootFsPath;
    private AndroidEmulator emulator;
    private VM vm;

    // Captured cipher state
    private byte[] aesKey;
    private byte[] aesIv;

    // BSS/state struct address — captured from the FUN_0012bed8 hook
    private long bssStateAddr = 0;

    public DecryptorHarness(String currentCore, String rootFsPath) {
        this.currentCore = currentCore;
        this.rootFsPath = rootFsPath;
    }

    public void run() throws Exception {
        // Setup rootfs directories and file index
        setupFileSystem();

        // Create ARM64 emulator using builder
        emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("com.tencent.mm")
                .setRootDir(new File(rootFsPath))
                .build();
        emulator.getMemory().setLibraryResolver(new AndroidResolver(23));

        // Create VM
        vm = emulator.createDalvikVM((File) null);
        vm.setJni(this);
        vm.setVerbose(false);

        // Define class hierarchy mapping for JNI method dispatch
        DvmClass inputStream = vm.resolveClass("java/io/InputStream");
        vm.resolveClass("java/io/FileInputStream", inputStream);
        vm.resolveClass("java/io/ByteArrayInputStream", inputStream);
        DvmClass filterInputStream = vm.resolveClass("java/io/FilterInputStream", inputStream);
        vm.resolveClass("java/io/BufferedInputStream", filterInputStream);
        vm.resolveClass("javax/crypto/CipherInputStream", filterInputStream);

        DvmClass zipFile = vm.resolveClass("java/util/zip/ZipFile");
        vm.resolveClass("java/util/jar/JarFile", zipFile);

        // JarEntry extends ZipEntry — needed so getCertificates() is found via the hierarchy
        DvmClass zipEntry = vm.resolveClass("java/util/zip/ZipEntry");
        vm.resolveClass("java/util/jar/JarEntry", zipEntry);

        DvmClass outputStream = vm.resolveClass("java/io/OutputStream");
        vm.resolveClass("java/io/ByteArrayOutputStream", outputStream);
        vm.resolveClass("java/io/FileOutputStream", outputStream);

        // Pre-register HookWrapper_s so FUN_0014c370's FindClass("HookWrapper_s") succeeds
        // instead of throwing ClassNotFoundException (which causes it to bail without loading the DEX).
        vm.resolveClass("com/android/xc/Wrapper/HookWrapper_s");

        // Load the native library
        File soFile = new File("../moduledata/x7_3.0/libwxcoreloader.so");
        DalvikModule dm = vm.loadLibrary(soFile, false);

        // Pre-allocate a 0x40-byte zeroed block to use as a "fake context" in the
        // FUN_0014094c hook below.  Allocation must happen outside the breakpoint callback
        // because calling malloc() from inside a BreakPointCallback triggers a
        // ConcurrentModificationException in unidbg's UniThreadDispatcher.
        final com.github.unidbg.memory.MemoryBlock fakeCtxBlock =
                emulator.getMemory().malloc(0x40, true);  // true = use jemalloc heap
        final long fakeCtxAddr =
                com.sun.jna.Pointer.nativeValue(fakeCtxBlock.getPointer());
        // Write the permanent "failure" marker into the block: context[+0x20] = -1
        // so the caller's `iVar3 = *(context + 0x20); if (iVar3 < 0)` always fires the
        // graceful failure path.
        fakeCtxBlock.getPointer().write(0, new byte[0x40], 0, 0x40);
        fakeCtxBlock.getPointer().setInt(0x20, -1);
        System.out.println("[Setup] Pre-allocated fake context @ 0x" + Long.toHexString(fakeCtxAddr));

        // Hook FUN_0014094c (msgpack decryption core).
        //
        // When given non-msgpack input (e.g. raw ELF loader) the function throws
        // std::runtime_error("insufficient bytes") — a C++ exception unidbg cannot unwind.
        // Short-circuit it by storing fakeCtxAddr into output_struct[+0x18] and jumping to LR.
        // The caller (FUN_00146314) reads:
        //   iVar3 = *(context_ptr + 0x20);   // = -1  → graceful failure path, no crash
        emulator.attach().addBreakPoint(dm.getModule().base + 0x4094c, new com.github.unidbg.debugger.BreakPointCallback() {
            @Override
            public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                com.github.unidbg.arm.context.RegisterContext context = emulator.getContext();
                com.sun.jna.Pointer x0 = context.getPointerArg(0);
                com.sun.jna.Pointer x1 = context.getPointerArg(1);
                if (x1 != null) {
                    byte[] magic = x1.getByteArray(0, 4);
                    int inputSize = context.getIntArg(2);
                    boolean isElf = magic[0] == 0x7f && magic[1] == 0x45 && magic[2] == 0x4c && magic[3] == 0x46;
                    boolean isZero = magic[0] == 0 && magic[1] == 0 && magic[2] == 0 && magic[3] == 0;
                    boolean shouldShortCircuit = isElf || isZero;
                    System.out.println("  [Hook] FUN_0014094c: isElf=" + isElf
                            + " isZero=" + isZero
                            + " inputSize=" + inputSize
                            + " shouldShortCircuit=" + shouldShortCircuit);
                    if (shouldShortCircuit && x1 != null && inputSize > 4) {
                        byte[] first16 = x1.getByteArray(0, Math.min(16, inputSize));
                        System.out.println("  [Hook] First bytes: " + java.util.HexFormat.of().formatHex(first16));
                    }
                    if (shouldShortCircuit) {
                        long outputStructAddr = com.sun.jna.Pointer.nativeValue(x0);
                        if (outputStructAddr == 0) {
                            // Null output struct — just skip (caller ignores output)
                            emulator.getBackend().reg_write(
                                    unicorn.Arm64Const.UC_ARM64_REG_PC, context.getLR());
                            return true;
                        }
                        // Write fakeCtxAddr into output_struct[+0x18] via Unicorn mem_write so
                        // the store actually reaches emulated memory (JNA Pointer.setLong() may
                        // operate on a JNA-side buffer copy, not the live emulator address space).
                        byte[] ptrBytes = java.nio.ByteBuffer.allocate(8)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .putLong(fakeCtxAddr)
                                .array();
                        emulator.getBackend().mem_write(outputStructAddr + 0x18, ptrBytes);
                        System.out.println("  [Hook] Non-msgpack: wrote fake context 0x"
                                + Long.toHexString(fakeCtxAddr)
                                + " -> output_struct[+0x18] @ 0x"
                                + Long.toHexString(outputStructAddr + 0x18));
                        emulator.getBackend().reg_write(
                                unicorn.Arm64Const.UC_ARM64_REG_PC, context.getLR());
                    }
                }
                return true;
            }
        });
        // Add breakpoint to intercept FUN_0012bed8 (key derivation) and dump derived base key
        final long[] savedX0 = new long[1];
        emulator.attach().addBreakPoint(dm.getModule().base + 0x2bed8, new com.github.unidbg.debugger.BreakPointCallback() {
            @Override
            public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                com.github.unidbg.arm.context.RegisterContext context = emulator.getContext();
                savedX0[0] = context.getPointerArg(0).peer;
                bssStateAddr = savedX0[0];   // save for between-call patching
                long lr = context.getLR();
                System.out.println("  [Hook] FUN_0012bed8 entry hit. BSS pointer = 0x" + Long.toHexString(savedX0[0]) + ", LR = 0x" + Long.toHexString(lr));
                
                emulator.attach().addBreakPoint(lr, new com.github.unidbg.debugger.BreakPointCallback() {
                    @Override
                    public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                        System.out.println("  [Hook] FUN_0012bed8 return hit. Dumping BSS string at 0x" + Long.toHexString(savedX0[0] + 0x58));
                        com.sun.jna.Pointer p = com.github.unidbg.pointer.UnidbgPointer.pointer(emulator, savedX0[0] + 0x58);
                        byte[] strBytes = p.getByteArray(0, 32);
                        System.out.println("  BSS bytes at 0x58 (string struct): " + java.util.HexFormat.of().formatHex(strBytes));
                        
                        com.sun.jna.Pointer p40 = com.github.unidbg.pointer.UnidbgPointer.pointer(emulator, savedX0[0] + 0x40);
                        byte[] strBytes40 = p40.getByteArray(0, 32);
                        System.out.println("  BSS bytes at 0x40 (string struct): " + java.util.HexFormat.of().formatHex(strBytes40));
                        return true;
                    }
                });
                return true;
            }
        });
        // Diagnostic breakpoint: FUN_0012a1fc is the CallMethod case-1 (decrypt+load) handler.
        // Early-return conditions (per decompilation):
        //   1. std::string at state_struct + 0x28 must be non-empty
        //   2. flag at state_struct + 0x20 must be NON-zero (= 1 means "initialized/active")
        // Use mem_read via the Unicorn backend — avoids JNA Pointer memory access issues in callbacks.
        emulator.attach().addBreakPoint(dm.getModule().base + 0x2a1fc, new com.github.unidbg.debugger.BreakPointCallback() {
            @Override
            public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                com.github.unidbg.arm.context.RegisterContext ctx = emulator.getContext();
                long stateAddr = ctx.getLongArg(0);
                System.out.println("  [Diag] FUN_0012a1fc entered. state_struct=0x"
                        + Long.toHexString(stateAddr));
                try {
                    // Read 0x60 bytes of the BSS state struct via Unicorn backend
                    byte[] raw = emulator.getBackend().mem_read(stateAddr, 0x60);
                    System.out.println("    raw[+0x00..+0x60]: " + java.util.HexFormat.of().formatHex(raw));
                    // Parse the flag at +0x20
                    byte flag20 = raw[0x20];
                    System.out.println("    [+0x20] flag = 0x" + Integer.toHexString(flag20 & 0xff)
                            + (flag20 == 0 ? "  ← ZERO: not initialized, will return early!" : "  ← non-zero: initialized ok"));
                    // Parse the std::string at +0x28 (libc++ SSO layout):
                    //   short mode: raw[0x28] bit0==0, length = raw[0x28] >> 1, data at [0x29..0x29+len]
                    //   long  mode: raw[0x28] bit0==1, length = *(uint64)(raw+0x30), ptr = *(uint64)(raw+0x38)
                    byte ssoByte = raw[0x28];
                    boolean isLong = (ssoByte & 1) != 0;
                    long strLen = isLong
                            ? java.nio.ByteBuffer.wrap(raw, 0x30, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong()
                            : ((ssoByte & 0xff) >> 1);
                    System.out.println("    [+0x28] string: sso_byte=0x" + Integer.toHexString(ssoByte & 0xff)
                            + " long=" + isLong + " length=" + strLen
                            + (strLen == 0 ? "  ← EMPTY: will return early!" : "  ← non-empty ok"));
                    if (strLen > 0 && strLen <= 32 && !isLong) {
                        byte[] strBytes = new byte[(int) strLen];
                        System.arraycopy(raw, 0x29, strBytes, 0, (int) strLen);
                        System.out.println("    [+0x28] string value: \""
                                + new String(strBytes, java.nio.charset.StandardCharsets.UTF_8) + "\"");
                    }
                } catch (Exception e) {
                    System.out.println("    [Diag] mem_read failed: " + e.getMessage());
                }
                return true;
            }
        });

        // Hook FUN_0014c19c (first loader in FUN_0014b9c4's 3-way chain) to return failure (1)
        // immediately.  This loader derives an AES key via PBE using real WeChat cert data,
        // which we don't have.  With our mock certs the key is wrong, produces 0-byte output,
        // and causes a mem_write crash at 0x18.  Forcing failure lets the code fall through to
        // FUN_0014c370 and ultimately FUN_0014c6d4 (Class loading / InMemoryDexClassLoader).
        emulator.attach().addBreakPoint(dm.getModule().base + 0x4c19c, new com.github.unidbg.debugger.BreakPointCallback() {
            @Override
            public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                System.out.println("  [Hook] FUN_0014c19c: forcing failure (return 1) — skip PBE loader");
                try {
                    emulator.getBackend().reg_write(unicorn.Arm64Const.UC_ARM64_REG_X0, 1);
                    emulator.getBackend().reg_write(unicorn.Arm64Const.UC_ARM64_REG_PC,
                            emulator.getContext().getLR());
                } catch (Exception e) {
                    System.err.println("  [Hook] FUN_0014c19c hook error: " + e.getMessage());
                }
                return true;
            }
        });
        // Hook FUN_0014c370 only for cores that need it to fail (e.g., C uses FUN_0014c6d4 directly).
        // Remove this hook to allow FUN_0014c370 to run naturally for M/S/H_p/P.
        emulator.attach().addBreakPoint(dm.getModule().base + 0x4cb68, new com.github.unidbg.debugger.BreakPointCallback() {
            @Override
            public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                System.out.println("  [Diag] FUN_0014cb68 called (lazy-init or load)");
                return true;
            }
        });
        emulator.attach().addBreakPoint(dm.getModule().base + 0x4445c, new com.github.unidbg.debugger.BreakPointCallback() {
            @Override
            public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                System.out.println("  [Diag] FUN_0014445c REACHED — about to call InMemoryDexClassLoader.<init>");
                return true;
            }
        });
        // (disabled — let it try)

        // Diagnostic hook at FUN_0014e7b0 — extracts "classes.dex" from a container.
        // If it returns null (jbyteArray), InMemoryDexClassLoader is never called.
        final long[] e7b0_x0 = new long[1];
        emulator.attach().addBreakPoint(dm.getModule().base + 0x4e7b0, new com.github.unidbg.debugger.BreakPointCallback() {
            @Override
            public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                com.github.unidbg.arm.context.RegisterContext ctx = emulator.getContext();
                e7b0_x0[0] = ctx.getLongArg(0);
                System.out.println("  [Diag] FUN_0014e7b0 entry x0=0x" + Long.toHexString(e7b0_x0[0]));
                long lr = ctx.getLR();
                emulator.attach().addBreakPoint(lr, new com.github.unidbg.debugger.BreakPointCallback() {
                    @Override
                    public boolean onHit(com.github.unidbg.Emulator<?> emulator, long address) {
                        com.github.unidbg.arm.context.RegisterContext ctx2 = emulator.getContext();
                        long ret = ctx2.getLongArg(0); // x0 = return value
                        System.out.println("  [Diag] FUN_0014e7b0 return x0=0x" + Long.toHexString(ret)
                                + (ret == 0 ? " ← null! DEX extract failed" : " ← OK"));
                        return true;
                    }
                });
                return true;
            }
        });

        dm.callJNI_OnLoad(emulator);

        System.out.println("\n========================================");
        System.out.println("Processing core: " + currentCore);
        System.out.println("========================================");

        // Resolve the Loader class
        DvmClass wxCoreLoader = vm.resolveClass("com/android/x/xposed/hook/WxCoreLoader");

        // Construct arguments for CallMethod(0, ...)
        // CallMethod(0, new Object[]{context, context, path, type, core, ver, modulePath})
        DvmObject<?> contextObj = vm.resolveClass("android/content/Context").newObject(null);
        String virtualPath = "/data/user/0/com.tencent.mm/files/x7_3.0";
        DvmObject<?>[] argsArray0 = new DvmObject<?>[]{
                contextObj,
                contextObj,
                new StringObject(vm, virtualPath),
                new StringObject(vm, "release"),
                new StringObject(vm, currentCore),
                DvmInteger.valueOf(vm, 1),
                new StringObject(vm, "/data/app/com.tencent.mm/base.apk")
        };
        ArrayObject args0 = new ArrayObject(argsArray0);

        System.out.println("Calling CallMethod(0, args) for " + currentCore + "...");
        wxCoreLoader.callStaticJniMethodObject(emulator, "CallMethod(I[Ljava/lang/Object;)Ljava/lang/Object;", 0, args0);

        // ── Post-CallMethod(0) state patch ──────────────────────────────────────────
        // FUN_0012c558 (called inside case-0 init) sets state_struct[+0x20] = 1 when
        // any of its "probe" sub-functions returns success (file-extension check, port
        // check, etc.).  In a real device all probes FAIL, leaving the byte = 0.
        // In our emulator one probe incorrectly returns success, so the byte ends up 1.
        // FUN_0014b9c4 (the InMemoryDexClassLoader loader) is gated by:
        //   if (state_struct[+0x20] == '\0') { /* load */ } else { return 0; /* disabled */ }
        // Fix: force the byte back to 0 so the loader can execute.
        if (bssStateAddr != 0) {
            try {
                byte[] flagBuf = emulator.getBackend().mem_read(bssStateAddr + 0x20, 1);
                System.out.println("[Patch] state_struct+0x20 after CallMethod(0): 0x"
                        + Integer.toHexString(flagBuf[0] & 0xff));
                emulator.getBackend().mem_write(bssStateAddr + 0x20, new byte[]{0});
                System.out.println("[Patch] Cleared state_struct+0x20 → 0  (enables FUN_0014b9c4 loader)");
            } catch (Exception e) {
                System.err.println("[Patch] WARNING: could not clear +0x20 flag: " + e.getMessage());
            }
        } else {
            System.err.println("[Patch] WARNING: bssStateAddr not captured — FUN_0012bed8 hook may not have fired");
        }

        // Construct arguments for CallMethod(1, ...)
        // CallMethod(1, new Object[]{classLoader, className, encrypt, is26})
        DvmObject<?> classLoaderObj = vm.resolveClass("java/lang/ClassLoader").newObject(null);
        DvmObject<?>[] argsArray1 = new DvmObject<?>[]{
                classLoaderObj,
                new StringObject(vm, "com.android.xc.Wrapper.HookWrapper"),
                DvmBoolean.valueOf(vm, true),
                DvmBoolean.valueOf(vm, true)
        };
        ArrayObject args1 = new ArrayObject(argsArray1);

        System.out.println("Calling CallMethod(1, args) for " + currentCore + "...");
        wxCoreLoader.callStaticJniMethodObject(emulator, "CallMethod(I[Ljava/lang/Object;)Ljava/lang/Object;", 1, args1);

        // Cleanup
        emulator.close();
    }

    private String getUnpaddedMd5(String s) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(s.getBytes());
            byte[] bArrDigest = messageDigest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bArrDigest) {
                sb.append(Integer.toHexString(b & 255));
            }
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    private void setupFileSystem() throws IOException {
        // Create rootfs folders
        String targetDirPath = rootFsPath + "/data/user/0/com.tencent.mm/files/x7_3.0";
        Files.createDirectories(Paths.get(targetDirPath));

        // Create rootfs apk directory and write dummy base.apk
        String apkDirPath = rootFsPath + "/data/app/com.tencent.mm";
        Files.createDirectories(Paths.get(apkDirPath));
        File baseApk = new File(apkDirPath, "base.apk");
        if (!baseApk.exists()) {
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new FileOutputStream(baseApk))) {
                zos.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));
                zos.write("dummy dex content".getBytes());
                zos.closeEntry();
            }
        }

        String coreMd5 = getUnpaddedMd5(currentCore);

        // Copy all files from moduledata/x7_3.0 to rootfs target directory
        File srcDir = new File("../moduledata/x7_3.0");
        File[] srcFiles = srcDir.listFiles();
        if (srcFiles != null) {
            for (File srcFile : srcFiles) {
                if (srcFile.isFile()) {
                    String name = srcFile.getName();
                    String destName = name;
                    if (name.equals(currentCore)) {
                        destName = coreMd5;
                    } else if (name.equals(currentCore + "_c")) {
                        destName = coreMd5 + "_c";
                    } else if (name.equals(currentCore + "_s")) {
                        destName = coreMd5 + "_s";
                    }
                    File destFile = new File(targetDirPath, destName);
                    if (!destFile.exists()) {
                        Files.copy(srcFile.toPath(), destFile.toPath());
                    }
                }
            }
        }

        // Copy loader to the architecture-specific name that the bridge expects for ARM64
        File loaderSrc = new File(srcDir, "dc7be70f1898d3a58965a919740f97");
        if (loaderSrc.exists()) {
            File destLoader = new File(targetDirPath, "53d9ad931411c35b69e2f990832d78fc");
            if (!destLoader.exists()) {
                Files.copy(loaderSrc.toPath(), destLoader.toPath());
            }
        }

        // Create a dummy signature file for the loader since it is missing but required for JNI verification
        File destLoaderSig = new File(targetDirPath, "53d9ad931411c35b69e2f990832d78fc_s");
        if (!destLoaderSig.exists()) {
            byte[] dummySig = new byte[256];
            Files.write(destLoaderSig.toPath(), dummySig);
        }

        // Create dummy _s signature companions for core files that don't have one.
        // Signature.verify() always returns true in our mock, so content is irrelevant.
        if (srcFiles != null) {
            for (File srcFile : srcFiles) {
                if (!srcFile.isFile() || srcFile.getName().contains("_")) continue;
                String name = srcFile.getName();
                String destName = name.equals(currentCore) ? coreMd5 : name;
                File destSig = new File(targetDirPath, destName + "_s");
                if (!destSig.exists()) {
                    Files.write(destSig.toPath(), new byte[256]);
                }
            }
        }

        // For cores whose main file IS the msgpack payload (no separate _c companion),
        // create a _c copy pointing to the main file.
        for (File srcFile : srcDir.listFiles()) {
            if (!srcFile.isFile()) continue;
            String name = srcFile.getName();
            // Skip files that already have a companion or are themselves companions
            if (name.contains("_") || name.endsWith(".so") || name.endsWith(".bin")
                    || name.endsWith(".dex") || name.endsWith(".der")) continue;
            String destName = name.equals(currentCore) ? coreMd5 : name;
            File cCompanion = new File(targetDirPath, destName + "_c");
            if (!cCompanion.exists()) {
                File mainInRootfs = new File(targetDirPath, destName);
                if (mainInRootfs.exists() && mainInRootfs.length() > 4) {
                    byte[] header = new byte[4];
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(mainInRootfs)) {
                        if (fis.read(header) == 4 && (header[0] & 0xff) == 0x97
                                && (header[1] & 0xff) == 0xc4 && (header[2] & 0xff) == 0x01
                                && (header[3] & 0xff) == 0x30) {
                            Files.copy(mainInRootfs.toPath(), cCompanion.toPath());
                            System.out.println("[setup] Created _c copy for " + destName + " (msgpack payload)");
                        }
                    } catch (IOException ignore) {}
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // JNI Emulation / Mocking
    // ------------------------------------------------------------------------

    @Override
    public DvmObject<?> callObjectMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        System.out.println("  [JNI Call] Object Method: " + signature);
        if (signature.equals("android/content/Context->getFilesDir()Ljava/io/File;")) {
            return vm.resolveClass("java/io/File").newObject(new File("/data/user/0/com.tencent.mm/files"));
        }
        if (signature.equals("android/content/Context->getPackageCodePath()Ljava/lang/String;")) {
            return new StringObject(vm, "/data/app/com.tencent.mm/base.apk");
        }
        if (signature.equals("android/content/Context->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;")) {
            return vm.resolveClass("android/content/SharedPreferences").newObject(null);
        }
        if (signature.equals("android/content/SharedPreferences->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")) {
            // Return default value
            return vaList.getObjectArg(1);
        }
        if (signature.equals("java/security/cert/CertificateFactory->generateCertificate(Ljava/io/InputStream;)Ljava/security/cert/Certificate;")) {
            try {
                CertificateFactory cf = (CertificateFactory) dvmObject.getValue();
                java.io.InputStream is = (java.io.InputStream) vaList.getObjectArg(0).getValue();
                java.security.cert.Certificate cert = cf.generateCertificate(is);
                return vm.resolveClass("java/security/cert/Certificate").newObject(cert);
            } catch (Exception e) {
                System.err.println("  [JNI] generateCertificate failed: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/security/cert/Certificate->getPublicKey()Ljava/security/PublicKey;")) {
            java.security.cert.Certificate cert = (java.security.cert.Certificate) dvmObject.getValue();
            // IMPORTANT: GetMethodID was called for "java/security/Key.getEncoded()[B" (not PublicKey).
            // Return as "java/security/Key" so that method ID is found in the correct class map.
            return vm.resolveClass("java/security/Key").newObject(cert.getPublicKey());
        }
        if (signature.equals("java/security/Key->getEncoded()[B")) {
            java.security.Key key = (java.security.Key) dvmObject.getValue();
            return new ByteArray(vm, key.getEncoded());
        }
        if (signature.equals("java/security/Key->getAlgorithm()Ljava/lang/String;")) {
            java.security.Key key = (java.security.Key) dvmObject.getValue();
            return new StringObject(vm, key.getAlgorithm());
        }
        if (signature.equals("java/security/cert/Certificate->getEncoded()[B")) {
            // If we have a real certificate object (from getCertificates on a real JAR),
            // use its actual encoded bytes. Otherwise fall back to the module's c.der cert.
            Object val = dvmObject.getValue();
            if (val instanceof java.security.cert.Certificate) {
                try {
                    byte[] encoded = ((java.security.cert.Certificate) val).getEncoded();
                    System.out.println("  [JNI] Certificate.getEncoded: real cert " + encoded.length + " bytes");
                    return new ByteArray(vm, encoded);
                } catch (Exception e) {
                    System.err.println("  [JNI] Certificate.getEncoded real cert failed: " + e.getMessage());
                }
            }
            // Fallback: module's own c.der cert
            try {
                byte[] certBytes = Files.readAllBytes(Paths.get("../moduledata/x7_3.0/534a9729a0c461cbd7a4379978fb742"));
                System.out.println("  [JNI] Certificate.getEncoded: using c.der fallback (" + certBytes.length + " bytes)");
                return new ByteArray(vm, certBytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("javax/crypto/Cipher->doFinal([B)[B")) {
            byte[] input = (byte[]) vaList.getObjectArg(0).getValue();
            try {
                Object val = dvmObject.getValue();
                if (val instanceof Cipher) {
                    // Use the real Cipher that was already init()-ed with the correct key/params
                    Cipher cipher = (Cipher) val;
                    byte[] output = cipher.doFinal(input);
                    System.out.println("  Decryption successful! Size: " + output.length + " bytes.");
                    return new ByteArray(vm, output);
                } else {
                    // Fallback: manual AES/CBC with stored aesKey/aesIv
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE,
                            new SecretKeySpec(aesKey, "AES"),
                            new IvParameterSpec(aesIv));
                    byte[] output = cipher.doFinal(input);
                    System.out.println("  Decryption (fallback) successful! Size: " + output.length + " bytes.");
                    return new ByteArray(vm, output);
                }
            } catch (Exception e) {
                System.err.println("  Decryption failed: " + e.getMessage());
                // Return empty array so the native code can detect failure and fall through
                // to the next loader attempt (FUN_0014c370, FUN_0014c6d4).
                return new ByteArray(vm, new byte[0]);
            }
        }
        if (signature.equals("java/security/MessageDigest->digest()[B")) {
            byte[] hash = ((MessageDigest) dvmObject.getValue()).digest();
            return new ByteArray(vm, hash);
        }
        if (signature.equals("java/io/File->getAbsolutePath()Ljava/lang/String;")) {
            File file = (File) dvmObject.getValue();
            return new StringObject(vm, file.getAbsolutePath());
        }
        if (signature.equals("java/security/Key->getEncoded()[B")) {
            java.security.Key key = (java.security.Key) dvmObject.getValue();
            return new ByteArray(vm, key.getEncoded());
        }
        if (signature.equals("java/security/Key->getAlgorithm()Ljava/lang/String;")) {
            java.security.Key key = (java.security.Key) dvmObject.getValue();
            return new StringObject(vm, key.getAlgorithm());
        }
        if (signature.equals("java/lang/String->toCharArray()[C")) {
            // PBEKeySpec takes a char[]; return as ByteArray with 2 bytes/char (little-endian)
            String s = dvmObject.getValue().toString();
            char[] chars = s.toCharArray();
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(chars.length * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (char c : chars) bb.putChar(c);
            return new ByteArray(vm, bb.array());
        }
        if (signature.equals("java/lang/String->getBytes()[B")) {
            String s = dvmObject.getValue().toString();
            System.out.println("  [JNI] String.getBytes() on: \"" + s + "\" (" + s.length() + " chars)");
            return new ByteArray(vm, s.getBytes());
        }
        if (signature.equals("java/lang/String->getBytes(Ljava/lang/String;)[B")) {
            String s = dvmObject.getValue().toString();
            String charset = vaList.getObjectArg(0).getValue().toString();
            try {
                return new ByteArray(vm, s.getBytes(charset));
            } catch (Exception e) {
                return new ByteArray(vm, s.getBytes());
            }
        }
        if (signature.equals("javax/crypto/SecretKey->getEncoded()[B")
                || signature.equals("javax/crypto/SecretKeySpec->getEncoded()[B")) {
            javax.crypto.SecretKey key = (javax.crypto.SecretKey) dvmObject.getValue();
            return new ByteArray(vm, key.getEncoded());
        }
        if (signature.equals("javax/crypto/SecretKeyFactory->generateSecret(Ljava/security/spec/KeySpec;)Ljavax/crypto/SecretKey;")) {
            javax.crypto.SecretKeyFactory skf = (javax.crypto.SecretKeyFactory) dvmObject.getValue();
            java.security.spec.KeySpec spec = (java.security.spec.KeySpec) vaList.getObjectArg(0).getValue();
            try {
                javax.crypto.SecretKey key = skf.generateSecret(spec);
                System.out.printf("  [JNI] SecretKeyFactory.generateSecret → %s (%d bytes)%n",
                        key.getAlgorithm(), key.getEncoded().length);
                return vm.resolveClass("javax/crypto/SecretKey").newObject(key);
            } catch (Exception e) {
                throw new RuntimeException("SecretKeyFactory.generateSecret failed: " + e.getMessage(), e);
            }
        }
        if (signature.equals("java/security/KeyFactory->generatePublic(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;")) {
            java.security.KeyFactory kf = (java.security.KeyFactory) dvmObject.getValue();
            java.security.spec.KeySpec spec = (java.security.spec.KeySpec) vaList.getObjectArg(0).getValue();
            try {
                java.security.PublicKey key = kf.generatePublic(spec);
                return vm.resolveClass("java/security/Key").newObject(key);  // typed as Key for getEncoded dispatch
            } catch (Exception e) {
                throw new RuntimeException("KeyFactory.generatePublic failed: " + e.getMessage(), e);
            }
        }
        if (signature.equals("java/security/KeyFactory->generatePrivate(Ljava/security/spec/KeySpec;)Ljava/security/PrivateKey;")) {
            java.security.KeyFactory kf = (java.security.KeyFactory) dvmObject.getValue();
            java.security.spec.KeySpec spec = (java.security.spec.KeySpec) vaList.getObjectArg(0).getValue();
            try {
                java.security.PrivateKey key = kf.generatePrivate(spec);
                return vm.resolveClass("java/security/Key").newObject(key);
            } catch (Exception e) {
                throw new RuntimeException("KeyFactory.generatePrivate failed: " + e.getMessage(), e);
            }
        }
        if (signature.equals("javax/crypto/KeyGenerator->generateKey()Ljavax/crypto/SecretKey;")) {
            javax.crypto.KeyGenerator kg = (javax.crypto.KeyGenerator) dvmObject.getValue();
            javax.crypto.SecretKey key = kg.generateKey();
            return vm.resolveClass("javax/crypto/SecretKey").newObject(key);
        }
        if (signature.equals("java/nio/ByteBuffer->array()[B")) {
            java.nio.ByteBuffer buf = (java.nio.ByteBuffer) dvmObject.getValue();
            return new ByteArray(vm, buf.array());
        }
        if (signature.equals("java/util/zip/ZipEntry->getName()Ljava/lang/String;")) {
            java.util.zip.ZipEntry entry = (java.util.zip.ZipEntry) dvmObject.getValue();
            return new StringObject(vm, entry.getName());
        }
        if (signature.equals("java/util/zip/ZipEntry->getCrc()J")
                || signature.equals("java/util/zip/ZipEntry->getSize()J")
                || signature.equals("java/util/zip/ZipEntry->getCompressedSize()J")) {
            // These are long-typed ZipEntry methods — handled in callLongMethodV instead
            return super.callObjectMethodV(vm, dvmObject, signature, vaList);
        }
        if (signature.equals("java/nio/ByteBuffer->wrap([B)Ljava/nio/ByteBuffer;")) {
            // static method, should be in callStaticObjectMethodV — but add here as safety
            byte[] b = (byte[]) vaList.getObjectArg(0).getValue();
            return vm.resolveClass("java/nio/ByteBuffer").newObject(java.nio.ByteBuffer.wrap(b));
        }
        if (signature.equals("java/util/jar/JarFile->getJarEntry(Ljava/lang/String;)Ljava/util/jar/JarEntry;")) {
            java.util.jar.JarFile jarFile = (java.util.jar.JarFile) dvmObject.getValue();
            DvmObject<?> arg = vaList.getObjectArg(0);
            if (arg == null || arg.getValue() == null) {
                return null;
            }
            String name = arg.getValue().toString();
            java.util.jar.JarEntry entry = jarFile.getJarEntry(name);
            return entry == null ? null : vm.resolveClass("java/util/jar/JarEntry").newObject(entry);
        }
        if (signature.equals("java/util/jar/JarFile->getEntry(Ljava/lang/String;)Ljava/util/zip/ZipEntry;")) {
            java.util.jar.JarFile jarFile = (java.util.jar.JarFile) dvmObject.getValue();
            DvmObject<?> arg = vaList.getObjectArg(0);
            if (arg == null || arg.getValue() == null) {
                return null;
            }
            String name = arg.getValue().toString();
            // Use getJarEntry() so the returned object has getCertificates() available
            java.util.jar.JarEntry entry = jarFile.getJarEntry(name);
            return entry == null ? null : vm.resolveClass("java/util/jar/JarEntry").newObject(entry);
        }
        if (signature.equals("java/util/jar/JarFile->getInputStream(Ljava/util/zip/ZipEntry;)Ljava/io/InputStream;")) {
            java.util.jar.JarFile jarFile = (java.util.jar.JarFile) dvmObject.getValue();
            DvmObject<?> arg = vaList.getObjectArg(0);
            if (arg == null || arg.getValue() == null) {
                return null;
            }
            java.util.zip.ZipEntry entry = (java.util.zip.ZipEntry) arg.getValue();
            try {
                java.io.InputStream is = jarFile.getInputStream(entry);
                return is == null ? null : vm.resolveClass("java/io/InputStream").newObject(is);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/util/jar/JarEntry->getCertificates()[Ljava/security/cert/Certificate;")) {
            Object val = dvmObject.getValue();
            if (val instanceof java.util.jar.JarEntry) {
                java.util.jar.JarEntry entry = (java.util.jar.JarEntry) val;
                java.security.cert.Certificate[] certs = entry.getCertificates();
                if (certs != null && certs.length > 0) {
                    DvmClass certClass = vm.resolveClass("java/security/cert/Certificate");
                    DvmObject<?>[] certObjs = new DvmObject<?>[certs.length];
                    for (int i = 0; i < certs.length; i++) {
                        certObjs[i] = certClass.newObject(certs[i]);
                    }
                    System.out.println("  [JNI] getCertificates: returning " + certs.length + " real cert(s)");
                    return new ArrayObject(certObjs);
                }
            }
            // Fallback: return the WeChatX module APK's actual signing cert so PBE
            // password derivation uses the right key material. getCertificates() is only
            // populated after the full stream is consumed and closed by the JarVerifier.
            // We pre-load the module cert as a fallback to avoid relying on that timing.
            try {
                byte[] certBytes = Files.readAllBytes(
                        Paths.get("../moduledata/x7_3.0/wechatx_module_cert.der"));
                java.security.cert.CertificateFactory cf =
                        java.security.cert.CertificateFactory.getInstance("X.509");
                java.security.cert.Certificate moduleCert =
                        cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
                DvmClass certClass2 = vm.resolveClass("java/security/cert/Certificate");
                System.out.println("  [JNI] getCertificates: returning module APK cert (fallback)");
                return new ArrayObject(new DvmObject<?>[]{certClass2.newObject(moduleCert)});
            } catch (Exception e) {
                System.err.println("  [JNI] getCertificates fallback failed: " + e.getMessage());
            }
            DvmClass certClass = vm.resolveClass("java/security/cert/Certificate");
            return new ArrayObject(new DvmObject<?>[]{certClass.newObject(null)});
        }
        if (signature.equals("java/util/zip/ZipInputStream->getNextEntry()Ljava/util/zip/ZipEntry;")) {
            java.util.zip.ZipInputStream zis = (java.util.zip.ZipInputStream) dvmObject.getValue();
            try {
                java.util.zip.ZipEntry entry = zis.getNextEntry();
                if (entry == null) return null;
                System.out.println("  [JNI] ZipInputStream.getNextEntry: \"" + entry.getName() + "\"");
                return vm.resolveClass("java/util/zip/ZipEntry").newObject(entry);
            } catch (IOException e) { return null; }
        }
        if (signature.equals("dalvik/system/InMemoryDexClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;")
                || signature.equals("java/lang/ClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;")) {
            // NOP — we already dumped the DEX in the constructor mock
            return vm.resolveClass("java/lang/Class").newObject(null);
        }
        if (signature.equals("java/io/ByteArrayOutputStream->toByteArray()[B")) {
            java.io.ByteArrayOutputStream baos = (java.io.ByteArrayOutputStream) dvmObject.getValue();
            byte[] data = baos.toByteArray();
            boolean isCert = data.length >= 2 && data[0] == 0x30 && data[1] == (byte)0x82;
            boolean isElf = data.length >= 4 && data[0] == 0x7f && data[1] == 0x45 && data[2] == 0x4c && data[3] == 0x46;
            boolean isEncrypted = data.length >= 4 && data[0] == (byte)0x97 && data[1] == (byte)0xc4 && data[2] == 0x01 && data[3] == 0x30;
            if (data.length > 1000 && !isCert && !isElf && !isEncrypted) {
                String outName = currentCore + "_decrypted.bin";
                File outFile = new File("../moduledata/x7_3.0/" + outName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(data);
                    System.out.println("  [JNI] ByteArrayOutputStream toByteArray saved " + data.length + " bytes to " + outFile.getAbsolutePath());
                } catch (IOException e) {
                    System.err.println("  Failed to save decrypted bin: " + e.getMessage());
                }
            }
            return new ByteArray(vm, data);
        }
        return super.callObjectMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public DvmObject<?> callStaticObjectMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        System.out.println("  [JNI Call] Static Object Method: " + signature);
        if (signature.equals("java/lang/Integer->toHexString(I)Ljava/lang/String;")) {
            int originalVal = vaList.getIntArg(0);
            // Mask away sign-extension but do NOT zero-pad: the native code intentionally
            // uses unpadded hex (e.g. 0x02 → "2", 0x0c → "c"). All real file names in
            // moduledata/ are produced with this same unpadded convention.
            String res = Integer.toHexString(originalVal & 0xff);
            return new StringObject(vm, res);
        }
        if (signature.equals("java/lang/Long->toHexString(J)Ljava/lang/String;")) {
            long val = vaList.getLongArg(0);
            return new StringObject(vm, Long.toHexString(val));
        }
        if (signature.equals("java/lang/Long->valueOf(J)Ljava/lang/Long;")) {
            long val = vaList.getLongArg(0);
            return vm.resolveClass("java/lang/Long").newObject(val);
        }
        if (signature.equals("java/lang/Integer->valueOf(I)Ljava/lang/Integer;")) {
            int val = vaList.getIntArg(0);
            return DvmInteger.valueOf(vm, val);
        }
        if (signature.equals("java/lang/String->valueOf(J)Ljava/lang/String;")) {
            long val = vaList.getLongArg(0);
            return new StringObject(vm, String.valueOf(val));
        }
        if (signature.equals("java/lang/String->format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;")) {
            // Return empty string — format calls are typically for debug/logging
            return new StringObject(vm, "");
        }
        if (signature.equals("javax/crypto/Cipher->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;")) {
            String transformation = vaList.getObjectArg(0).getValue().toString();
            System.out.println("  [JNI] Cipher.getInstance(\"" + transformation + "\")");
            // Store the real Cipher object so init/doFinal can use it directly.
            // Android uses PKCS7Padding; standard Java JCE only knows PKCS5Padding — they're identical for AES.
            String jceTransformation = transformation.replace("PKCS7Padding", "PKCS5Padding");
            try {
                Cipher realCipher = Cipher.getInstance(jceTransformation);
                return vm.resolveClass("javax/crypto/Cipher").newObject(realCipher);
            } catch (Exception e) {
                System.err.println("  [JNI] Cipher.getInstance failed for \"" + jceTransformation + "\": " + e.getMessage());
                return vm.resolveClass("javax/crypto/Cipher").newObject(transformation);
            }
        }
        if (signature.equals("java/security/cert/CertificateFactory->getInstance(Ljava/lang/String;)Ljava/security/cert/CertificateFactory;")) {
            try {
                String algorithm = vaList.getObjectArg(0).getValue().toString();
                return vm.resolveClass("java/security/cert/CertificateFactory")
                        .newObject(CertificateFactory.getInstance(algorithm));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/security/MessageDigest->getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;")) {
            try {
                String algorithm = vaList.getObjectArg(0).getValue().toString();
                return vm.resolveClass("java/security/MessageDigest").newObject(MessageDigest.getInstance(algorithm));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/security/Signature->getInstance(Ljava/lang/String;)Ljava/security/Signature;")) {
            try {
                String algorithm = vaList.getObjectArg(0).getValue().toString();
                return vm.resolveClass("java/security/Signature").newObject(Signature.getInstance(algorithm));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("javax/crypto/SecretKeyFactory->getInstance(Ljava/lang/String;)Ljavax/crypto/SecretKeyFactory;")) {
            try {
                String algorithm = vaList.getObjectArg(0).getValue().toString();
                return vm.resolveClass("javax/crypto/SecretKeyFactory")
                        .newObject(javax.crypto.SecretKeyFactory.getInstance(algorithm));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/security/KeyFactory->getInstance(Ljava/lang/String;)Ljava/security/KeyFactory;")) {
            try {
                String algorithm = vaList.getObjectArg(0).getValue().toString();
                return vm.resolveClass("java/security/KeyFactory")
                        .newObject(java.security.KeyFactory.getInstance(algorithm));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("javax/crypto/KeyGenerator->getInstance(Ljava/lang/String;)Ljavax/crypto/KeyGenerator;")) {
            try {
                String algorithm = vaList.getObjectArg(0).getValue().toString();
                return vm.resolveClass("javax/crypto/KeyGenerator")
                        .newObject(javax.crypto.KeyGenerator.getInstance(algorithm));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/security/SecureRandom->getInstance(Ljava/lang/String;)Ljava/security/SecureRandom;")
                || signature.equals("java/security/SecureRandom->getInstance()Ljava/security/SecureRandom;")) {
            return vm.resolveClass("java/security/SecureRandom").newObject(new java.security.SecureRandom());
        }
        if (signature.equals("java/nio/ByteBuffer->wrap([B)Ljava/nio/ByteBuffer;")) {
            byte[] b = (byte[]) vaList.getObjectArg(0).getValue();
            if (b == null) {
                System.err.println("  [JNI] ByteBuffer.wrap: null array — FUN_0014e7b0 failed to extract classes.dex");
                return super.callStaticObjectMethodV(vm, dvmClass, signature, vaList);
            }
            System.out.println("  [JNI] ByteBuffer.wrap(" + b.length + " bytes)");
            if (b.length > 4) {
                System.out.println("  [JNI] First bytes: " + java.util.HexFormat.of().formatHex(java.util.Arrays.copyOf(b, Math.min(8, b.length))));
            }
            return vm.resolveClass("java/nio/ByteBuffer").newObject(java.nio.ByteBuffer.wrap(b));
        }
        return super.callStaticObjectMethodV(vm, dvmClass, signature, vaList);
    }

    private String translatePath(String path) {
        if (path == null) return null;
        if (path.startsWith("/")) {
            if (!path.startsWith("/home/") && !path.contains("rootfs")) {
                return "rootfs" + path;
            }
        }
        return path;
    }

    @Override
    public DvmObject<?> newObjectV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        System.out.println("  [JNI Call] New Object: " + signature);
        if (signature.equals("java/io/File-><init>(Ljava/lang/String;)V")) {
            String path = vaList.getObjectArg(0).getValue().toString();
            System.out.println("  [JNI] new File(" + path + ")");
            path = translatePath(path);
            return dvmClass.newObject(new File(path));
        }
        if (signature.equals("java/io/File-><init>(Ljava/lang/String;Ljava/lang/String;)V")) {
            String parent = vaList.getObjectArg(0).getValue().toString();
            String child = vaList.getObjectArg(1).getValue().toString();
            System.out.println("  [JNI] new File(" + parent + ", " + child + ")");
            parent = translatePath(parent);
            return dvmClass.newObject(new File(parent, child));
        }        if (signature.equals("java/util/jar/JarFile-><init>(Ljava/lang/String;)V")) {
            String path = vaList.getObjectArg(0).getValue().toString();
            path = translatePath(path);
            try {
                return dvmClass.newObject(new java.util.jar.JarFile(path));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/io/FileInputStream-><init>(Ljava/lang/String;)V")) {
            String path = vaList.getObjectArg(0).getValue().toString();
            path = translatePath(path);
            try {
                return dvmClass.newObject(new java.io.FileInputStream(path));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/io/FileInputStream-><init>(Ljava/io/File;)V")) {            File file = (File) vaList.getObjectArg(0).getValue();
            try {
                return dvmClass.newObject(new java.io.FileInputStream(file));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/io/ByteArrayOutputStream-><init>()V")) {
            return dvmClass.newObject(new java.io.ByteArrayOutputStream());
        }
        if (signature.equals("java/io/FileOutputStream-><init>(Ljava/lang/String;)V")
                || signature.equals("java/io/FileOutputStream-><init>(Ljava/lang/String;Z)V")) {
            String path = vaList.getObjectArg(0).getValue().toString();
            boolean append = signature.contains(";Z)") && vaList.getIntArg(1) != 0;
            path = translatePath(path);
            try {
                return dvmClass.newObject(new java.io.FileOutputStream(path, append));
            } catch (IOException e) { throw new RuntimeException(e); }
        }
        if (signature.equals("java/io/FileOutputStream-><init>(Ljava/io/File;)V")
                || signature.equals("java/io/FileOutputStream-><init>(Ljava/io/File;Z)V")) {
            File file = (File) vaList.getObjectArg(0).getValue();
            boolean append = signature.contains(";Z)") && vaList.getIntArg(1) != 0;
            try {
                return dvmClass.newObject(new java.io.FileOutputStream(file, append));
            } catch (IOException e) { throw new RuntimeException(e); }
        }
        if (signature.equals("java/io/BufferedInputStream-><init>(Ljava/io/InputStream;)V")) {
            java.io.InputStream inner = (java.io.InputStream) vaList.getObjectArg(0).getValue();
            return dvmClass.newObject(new java.io.BufferedInputStream(inner));
        }
        if (signature.equals("java/io/BufferedInputStream-><init>(Ljava/io/InputStream;I)V")) {
            java.io.InputStream inner = (java.io.InputStream) vaList.getObjectArg(0).getValue();
            int bufSize = vaList.getIntArg(1);
            return dvmClass.newObject(new java.io.BufferedInputStream(inner, bufSize));
        }
        if (signature.equals("java/io/ByteArrayInputStream-><init>([B)V")) {
            byte[] bytes = (byte[]) vaList.getObjectArg(0).getValue();
            return dvmClass.newObject(new java.io.ByteArrayInputStream(bytes));
        }
        if (signature.equals("java/util/zip/CRC32-><init>()V")) {
            return dvmClass.newObject(new java.util.zip.CRC32());
        }
        if (signature.equals("javax/crypto/spec/SecretKeySpec-><init>([BLjava/lang/String;)V") ||
                signature.equals("javax/crypto/spec/SecretKeySpec-><init>([BIILjava/lang/String;)V")) {
            byte[] keyBytes = (byte[]) vaList.getObjectArg(0).getValue();
            // Guard: SecretKeySpec rejects empty keys. Use 16 zero bytes as a dummy fallback so
            // the cipher can at least initialize. The resulting decryption will produce garbage
            // which the native code will detect and fall through to the next loader path.
            if (keyBytes == null || keyBytes.length == 0) {
                System.out.println("  [JNI] SecretKeySpec: received empty key — using 16-byte zero dummy");
                keyBytes = new byte[16];
            }
            this.aesKey = keyBytes;
            System.out.printf("  [JNI] SecretKeySpec key (%d bytes): %s%n",
                    keyBytes.length, java.util.HexFormat.of().formatHex(keyBytes));
            String algo = vaList.getObjectArg(signature.contains("BIIL") ? 3 : 1).getValue().toString();
            return dvmClass.newObject(new SecretKeySpec(keyBytes, algo));
        }
        if (signature.equals("javax/crypto/spec/IvParameterSpec-><init>([B)V")) {
            byte[] ivBytes = (byte[]) vaList.getObjectArg(0).getValue();
            this.aesIv = ivBytes;
            System.out.printf("  [JNI] IvParameterSpec IV  (%d bytes): %s%n",
                    ivBytes.length, java.util.HexFormat.of().formatHex(ivBytes));
            return dvmClass.newObject(new IvParameterSpec(ivBytes));
        }
        // PBE / PBKDF2 key derivation objects
        if (signature.equals("javax/crypto/spec/PBEKeySpec-><init>([C)V")) {
            char[] password = null;
            DvmObject<?> arg = vaList.getObjectArg(0);
            if (arg != null && arg.getValue() instanceof byte[]) {
                byte[] b = (byte[]) arg.getValue();
                password = new String(b, java.nio.charset.StandardCharsets.UTF_16LE).toCharArray();
            }
            if (password != null) {
                System.out.printf("  [JNI] PBEKeySpec password: \"%s\" (%d chars)%n",
                        new String(password), password.length);
            }
            try {
                return dvmClass.newObject(new javax.crypto.spec.PBEKeySpec(
                        password != null ? password : new char[0]));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        if (signature.equals("javax/crypto/spec/PBEKeySpec-><init>([C[BI)V")
                || signature.equals("javax/crypto/spec/PBEKeySpec-><init>([C[BII)V")) {
            char[] password = null;
            DvmObject<?> arg0 = vaList.getObjectArg(0);
            if (arg0 != null && arg0.getValue() instanceof byte[]) {
                byte[] b = (byte[]) arg0.getValue();
                password = new String(b, java.nio.charset.StandardCharsets.UTF_16LE).toCharArray();
            }
            byte[] salt = (byte[]) vaList.getObjectArg(1).getValue();
            int iterations = vaList.getIntArg(2);
            int keyLen = signature.contains("II") ? vaList.getIntArg(3) : 128;
            try {
                return dvmClass.newObject(new javax.crypto.spec.PBEKeySpec(
                        password != null ? password : new char[0], salt, iterations, keyLen));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        if (signature.equals("javax/crypto/spec/PBEParameterSpec-><init>([BI)V")) {
            byte[] salt = (byte[]) vaList.getObjectArg(0).getValue();
            int iterations = vaList.getIntArg(1);
            System.out.printf("  [JNI] PBEParameterSpec salt=%s iterations=%d%n",
                    java.util.HexFormat.of().formatHex(salt), iterations);
            return dvmClass.newObject(new javax.crypto.spec.PBEParameterSpec(salt, iterations));
        }
        if (signature.equals("java/security/spec/X509EncodedKeySpec-><init>([B)V")) {
            byte[] encoded = (byte[]) vaList.getObjectArg(0).getValue();
            return dvmClass.newObject(new java.security.spec.X509EncodedKeySpec(encoded));
        }
        if (signature.equals("java/security/spec/PKCS8EncodedKeySpec-><init>([B)V")) {
            byte[] encoded = (byte[]) vaList.getObjectArg(0).getValue();
            return dvmClass.newObject(new java.security.spec.PKCS8EncodedKeySpec(encoded));
        }
        if (signature.equals("java/security/SecureRandom-><init>()V")) {
            return dvmClass.newObject(new java.security.SecureRandom());
        }
        if (signature.equals("javax/crypto/CipherInputStream-><init>(Ljava/io/InputStream;Ljavax/crypto/Cipher;)V")) {
            java.io.InputStream is = (java.io.InputStream) vaList.getObjectArg(0).getValue();
            Cipher cipher = (Cipher) vaList.getObjectArg(1).getValue();
            return dvmClass.newObject(new javax.crypto.CipherInputStream(is, cipher));
        }
        if (signature.equals("java/util/zip/ZipInputStream-><init>(Ljava/io/InputStream;)V")) {
            java.io.InputStream is = (java.io.InputStream) vaList.getObjectArg(0).getValue();
            return dvmClass.newObject(new java.util.zip.ZipInputStream(is));
        }
        if (signature.equals("java/io/BufferedWriter-><init>(Ljava/io/Writer;)V")
                || signature.equals("java/io/PrintWriter-><init>(Ljava/io/Writer;)V")) {
            // Not needed for core decryption — return a no-op writer
            return dvmClass.newObject(null);
        }
        if (signature.equals("java/lang/Float-><init>(F)V")) {
            float v = vaList.getFloatArg(0);
            return dvmClass.newObject(v);
        }
        if (signature.equals("java/lang/Double-><init>(D)V")) {
            double v = vaList.getDoubleArg(0);
            return dvmClass.newObject(v);
        }
        if (signature.equals("dalvik/system/InMemoryDexClassLoader-><init>(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V")) {            DvmObject<?> byteBufferObj = vaList.getObjectArg(0);
            ByteBuffer byteBuffer = (ByteBuffer) byteBufferObj.getValue();
            byte[] dexBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(dexBytes);

            String outName = currentCore + "_decrypted.dex";
            File outFile = new File("../moduledata/x7_3.0/" + outName);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(dexBytes);
                System.out.println("  [JNI] InMemoryDexClassLoader loaded! Saved " + dexBytes.length + " bytes to " + outFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("  Failed to save decrypted DEX: " + e.getMessage());
            }
            return dvmClass.newObject(null);
        }
        return super.newObjectV(vm, dvmClass, signature, vaList);
    }

    @Override
    public void callVoidMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if (signature.startsWith("javax/crypto/Cipher->init(")) {
            // Initialize the real Cipher stored in dvmObject.
            try {
                Object val = dvmObject.getValue();
                if (!(val instanceof Cipher)) {
                    System.err.println("  [JNI] Cipher.init: dvmObject is not a Cipher: " + (val == null ? "null" : val.getClass().getName()));
                    return;
                }
                Cipher cipher = (Cipher) val;
                int mode = vaList.getIntArg(0);
                DvmObject<?> keyObj = vaList.getObjectArg(1);
                java.security.Key key = null;
                if (keyObj != null && keyObj.getValue() instanceof java.security.Key) {
                    key = (java.security.Key) keyObj.getValue();
                    System.out.printf("  [JNI] Cipher.init mode=%d key=%s (%d bytes)%n",
                            mode, key.getAlgorithm(), key.getEncoded().length);
                    // Also cache AES key for fallback path
                    if ("AES".equals(key.getAlgorithm())) aesKey = key.getEncoded();
                } else {
                    System.out.println("  [JNI] Cipher.init: no key object, sig=" + signature);
                    return;
                }
                DvmObject<?> paramObj = null;
                try { paramObj = vaList.getObjectArg(2); } catch (Exception ignore) {}
                if (paramObj != null && paramObj.getValue() instanceof java.security.spec.AlgorithmParameterSpec) {
                    java.security.spec.AlgorithmParameterSpec spec =
                            (java.security.spec.AlgorithmParameterSpec) paramObj.getValue();
                    cipher.init(mode, key, spec);
                    if (spec instanceof javax.crypto.spec.IvParameterSpec)
                        aesIv = ((javax.crypto.spec.IvParameterSpec) spec).getIV();
                } else {
                    cipher.init(mode, key);
                }
            } catch (Exception e) {
                System.err.println("  [JNI] Cipher.init failed: " + e.getMessage());
            }
            return;
        }
        if (signature.equals("java/security/MessageDigest->update([B)V")) {
            byte[] bytes = (byte[]) vaList.getObjectArg(0).getValue();
            ((MessageDigest) dvmObject.getValue()).update(bytes);
            return;
        }
        if (signature.equals("java/util/zip/CRC32->update([B)V")) {
            byte[] bytes = (byte[]) vaList.getObjectArg(0).getValue();
            ((java.util.zip.CRC32) dvmObject.getValue()).update(bytes);
            return;
        }
        if (signature.equals("java/util/zip/CRC32->update([BII)V")) {
            byte[] bytes = (byte[]) vaList.getObjectArg(0).getValue();
            int off = vaList.getIntArg(1);
            int len = vaList.getIntArg(2);
            ((java.util.zip.CRC32) dvmObject.getValue()).update(bytes, off, len);
            return;
        }
        if (signature.equals("java/util/zip/CRC32->reset()V")) {
            ((java.util.zip.CRC32) dvmObject.getValue()).reset();
            return;
        }
        if (signature.equals("java/security/Signature->initVerify(Ljava/security/PublicKey;)V")) {
            return; // NOP
        }
        if (signature.equals("java/security/Signature->update([B)V")) {
            return; // NOP
        }
        if (signature.equals("java/security/Signature->update([BII)V")) {
            return; // NOP — signature verification is bypassed via verify() always returning true
        }
        if (signature.equals("java/util/jar/JarFile->close()V")) {
            java.util.jar.JarFile jarFile = (java.util.jar.JarFile) dvmObject.getValue();
            try {
                jarFile.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (signature.equals("java/util/zip/ZipFile->close()V")) {
            // JarFile is a ZipFile — closing via the ZipFile interface
            Object val = dvmObject.getValue();
            try {
                if (val instanceof java.util.jar.JarFile) ((java.util.jar.JarFile) val).close();
                else if (val instanceof java.util.zip.ZipFile) ((java.util.zip.ZipFile) val).close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (signature.equals("java/io/InputStream->close()V")) {
            java.io.InputStream is = (java.io.InputStream) dvmObject.getValue();
            try {
                is.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (signature.equals("java/util/zip/ZipInputStream->closeEntry()V")
                || signature.equals("java/util/zip/ZipInputStream->close()V")) {
            java.util.zip.ZipInputStream zis = (java.util.zip.ZipInputStream) dvmObject.getValue();
            try {
                if (signature.contains("closeEntry")) zis.closeEntry(); else zis.close();
            } catch (IOException e) { /* ignore */ }
            return;
        }
        if (signature.startsWith("java/io/OutputStream->close()V")
                || signature.startsWith("java/io/ByteArrayOutputStream->close()V")
                || signature.startsWith("java/io/FileOutputStream->close()V")) {            java.io.OutputStream os = (java.io.OutputStream) dvmObject.getValue();
            try {
                os.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (signature.startsWith("java/io/OutputStream->write([BII)V")
                || signature.startsWith("java/io/ByteArrayOutputStream->write([BII)V")
                || signature.startsWith("java/io/FileOutputStream->write([BII)V")) {
            java.io.OutputStream os = (java.io.OutputStream) dvmObject.getValue();
            byte[] b = (byte[]) vaList.getObjectArg(0).getValue();
            int off = vaList.getIntArg(1);
            int len = vaList.getIntArg(2);
            try {
                os.write(b, off, len);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (signature.startsWith("java/io/OutputStream->write([B)V")
                || signature.startsWith("java/io/ByteArrayOutputStream->write([B)V")
                || signature.startsWith("java/io/FileOutputStream->write([B)V")) {
            java.io.OutputStream os = (java.io.OutputStream) dvmObject.getValue();
            byte[] b = (byte[]) vaList.getObjectArg(0).getValue();
            try {
                os.write(b);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (signature.equals("java/util/zip/ZipInputStream->closeEntry()V")) {
            java.util.zip.ZipInputStream zis = (java.util.zip.ZipInputStream) dvmObject.getValue();
            try { zis.closeEntry(); } catch (IOException e) { /* ignore */ }
            return;
        }
        super.callVoidMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public boolean callBooleanMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        System.out.println("  [JNI Call] Boolean Method: " + signature);
        if (signature.equals("java/io/File->createNewFile()Z")) {
            File file = (File) dvmObject.getValue();
            try { return file.createNewFile(); } catch (IOException e) { return false; }
        }
        if (signature.equals("java/io/File->delete()Z")) {
            File file = (File) dvmObject.getValue();
            return file.delete();
        }
        if (signature.equals("java/io/File->mkdirs()Z")
                || signature.equals("java/io/File->mkdir()Z")) {
            File file = (File) dvmObject.getValue();
            return file.mkdirs();
        }
        if (signature.equals("java/security/Signature->verify([B)Z")) {
            System.out.println("  [JNI] Signature.verify() -> bypassing and returning true.");
            return true;
        }
        if (signature.equals("java/lang/Boolean->booleanValue()Z")) {
            return ((DvmBoolean) dvmObject).getValue();
        }
        if (signature.equals("java/io/File->exists()Z")) {
            File file = (File) dvmObject.getValue();
            return file.exists();
        }
        if (signature.equals("java/io/File->isDirectory()Z")) {
            File file = (File) dvmObject.getValue();
            return file.isDirectory();
        }
        if (signature.equals("java/io/File->isFile()Z")) {
            File file = (File) dvmObject.getValue();
            return file.isFile();
        }
        return super.callBooleanMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public int callIntMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        System.out.println("  [JNI Call] Int Method: " + signature);
        if (signature.startsWith("javax/crypto/CipherInputStream->read(") ||
                signature.startsWith("javax/crypto/CipherInputStream->available(")) {
            // Delegate to the underlying stream via the real CipherInputStream
            java.io.InputStream is = (java.io.InputStream) dvmObject.getValue();
            try {
                if (signature.contains("read([B)")) {
                    byte[] b = (byte[]) vaList.getObjectArg(0).getValue();
                    return is.read(b);
                } else {
                    return is.available();
                }
            } catch (IOException e) {
                return -1;
            }
        }
        if (signature.equals("java/util/zip/ZipInputStream->read([B)I")) {
            java.util.zip.ZipInputStream zis = (java.util.zip.ZipInputStream) dvmObject.getValue();
            byte[] b = (byte[]) vaList.getObjectArg(0).getValue();
            try { return zis.read(b); } catch (IOException e) { return -1; }
        }
        if (signature.equals("java/io/InputStream->available()I")) {
            java.io.InputStream is = (java.io.InputStream) dvmObject.getValue();
            try {
                return is.available();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (signature.equals("java/io/InputStream->read([B)I")) {
            java.io.InputStream is = (java.io.InputStream) dvmObject.getValue();
            byte[] b = (byte[]) vaList.getObjectArg(0).getValue();
            try {
                return is.read(b);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return super.callIntMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public long callLongMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if (signature.equals("java/io/File->length()J")) {
            File file = (File) dvmObject.getValue();
            return file.length();
        }
        if (signature.equals("java/util/zip/CRC32->getValue()J")) {
            return ((java.util.zip.CRC32) dvmObject.getValue()).getValue();
        }
        return super.callLongMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public long callStaticLongMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        if (signature.equals("java/lang/System->currentTimeMillis()J")) {
            return System.currentTimeMillis();
        }
        if (signature.equals("java/lang/Long->parseLong(Ljava/lang/String;I)J")) {
            String s = vaList.getObjectArg(0).getValue().toString();
            int radix = vaList.getIntArg(1);
            return Long.parseLong(s, radix);
        }
        if (signature.equals("java/lang/Long->parseLong(Ljava/lang/String;)J")) {
            String s = vaList.getObjectArg(0).getValue().toString();
            return Long.parseLong(s);
        }
        return super.callStaticLongMethodV(vm, dvmClass, signature, vaList);
    }


    // ------------------------------------------------------------------------
    // Main execution entry point
    // ------------------------------------------------------------------------

    public static void main(String[] args) {
        String[] cores = {
                "C", "M", "S", "H_p", "P",
                "14e777717682cce8a53cfcb34a22d65",
                "802766a4a4bd401c145a57463274d4dd",
                "a89eaf67dc796cb3af54d94fac0198b",
                "df4e6fd144fab2389ece8cb79eaa8f",
                "9816b5dfd637d3ce1473a9951b9e05"
        };
        String rootFsPath = "rootfs";

        // Clean any old rootfs files to prevent state pollution
        deleteDir(new File(rootFsPath));

        int successCount = 0;
        for (String core : cores) {
            try {
                DecryptorHarness harness = new DecryptorHarness(core, rootFsPath);
                harness.run();
                successCount++;
            } catch (Exception e) {
                System.err.println("Error processing core: " + core);
                e.printStackTrace();
            }
        }
        System.out.println("\nDone! Successfully processed " + successCount + " / " + cores.length + " cores.");
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }
}
