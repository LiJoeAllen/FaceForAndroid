"""检查 APK / AAR / .so 的 ELF 段是否满足 Android 16 KB 内存页要求。

Android 15 起设备可启用 16 KB 内存页，动态链接器要求每个 PT_LOAD 段的
p_align 不小于 16 KB。NDK r27 默认仍按 4 KB 链接，升级/发布前用本脚本
一次性扫出所有 .so，避免只在真机启动时才暴露 dlopen 失败。

用法: python check_16k_alignment.py <apk|aar|so|目录>
  - APK 扫描 zip 内的 lib/<abi>/*.so
  - AAR 扫描 zip 内的 jni/<abi>/*.so
  - 目录递归扫描 *.so
  - 32 位 ABI（armeabi-v7a/x86）只告警不判失败，Google Play 的 16 KB
    强制要求仅覆盖 64 位 ABI
退出码: 0 全部通过（或仅有 32 位告警），1 存在不达标的 64 位库，
        2 未找到任何 .so。
"""
import struct
import sys
import zipfile
from pathlib import Path

# Windows 控制台默认使用 GBK，中英文混排的输出会乱码；统一按 UTF-8 写。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PT_LOAD = 1
REQUIRED_ALIGN = 16 * 1024
ABI64 = {"arm64-v8a", "x86_64", "riscv64"}


def parse_elf(data: bytes):
    """返回 (ELF 位数, [(段类型, p_align), ...])；不是合法 ELF 时抛 ValueError。"""
    if len(data) < 0x40 or data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")
    elf_class = data[4]
    if data[5] != 1:  # 仅处理小端
        raise ValueError("不支持大端 ELF")
    if elf_class == 2:  # ELF64
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        e_phnum = struct.unpack_from("<H", data, 0x38)[0]
        p_type_off, p_align_off = 0, 0x30
    elif elf_class == 1:  # ELF32
        e_phoff = struct.unpack_from("<I", data, 0x1C)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x2A)[0]
        e_phnum = struct.unpack_from("<H", data, 0x2C)[0]
        p_type_off, p_align_off = 0, 0x1C
    else:
        raise ValueError(f"未知 ELF class: {elf_class}")

    headers = []
    align_size = 8 if elf_class == 2 else 4
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        if base + p_align_off + align_size > len(data):
            raise ValueError("程序头超出文件范围")
        p_type = struct.unpack_from("<I", data, base + p_type_off)[0]
        p_align = struct.unpack_from("<Q" if elf_class == 2 else "<I",
                                     data, base + p_align_off)[0]
        headers.append((p_type, p_align))
    return (64 if elf_class == 2 else 32), headers


def collect_libraries(target: Path):
    """按输入类型产出 (显示名, ABI 或 None, .so 字节)。"""
    suffix = target.suffix.lower()
    if target.is_dir():
        for so in sorted(target.rglob("*.so")):
            # 目录输入没有可靠的 ABI 信息，交给 ELF class 判断
            yield so.as_posix(), None, so.read_bytes()
        return
    if suffix == ".so":
        yield target.as_posix(), None, target.read_bytes()
        return
    if suffix in (".apk", ".aar", ".zip"):
        with zipfile.ZipFile(target) as archive:
            for name in archive.namelist():
                parts = name.split("/")
                # APK: lib/<abi>/xxx.so；AAR: jni/<abi>/xxx.so
                if len(parts) == 3 and parts[0] in ("lib", "jni") and name.endswith(".so"):
                    yield f"{target.name}:{name}", parts[1], archive.read(name)
        return
    raise ValueError(f"不支持的文件类型: {target}")


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    target = Path(sys.argv[1])
    if not target.exists():
        print(f"路径不存在: {target}")
        return 2

    checked = 0
    failures = []
    warnings = []
    try:
        for name, abi, data in collect_libraries(target):
            try:
                bits, headers = parse_elf(data)
            except (ValueError, struct.error) as e:
                print(f"[跳过] {name}: {e}")
                continue
            loads = [align for p_type, align in headers if p_type == PT_LOAD]
            if not loads:
                print(f"[跳过] {name}: 没有 PT_LOAD 段")
                continue
            min_align = min(loads)
            checked += 1
            is64 = abi in ABI64 if abi else bits == 64
            state = "OK" if min_align >= REQUIRED_ALIGN else ("FAIL" if is64 else "警告")
            print(f"[{state}] {name} ABI={abi or f'ELF{bits}'} PT_LOAD p_align={min_align}")
            if min_align < REQUIRED_ALIGN:
                (failures if is64 else warnings).append(name)
    except (ValueError, zipfile.BadZipFile, struct.error) as e:
        # 非法输入（未知后缀、损坏压缩包等）按用法错误处理，不抛 traceback。
        print(f"读取失败: {e}")
        return 2

    if checked == 0:
        print("未找到可检查的 .so")
        return 2
    if failures:
        print(f"\n失败: {len(failures)} 个 64 位库的 PT_LOAD 未按 16 KB 对齐")
        return 1
    print(f"\n通过: 共检查 {checked} 个 .so"
          + (f"，{len(warnings)} 个 32 位库低于 16 KB（不影响 Play 要求）" if warnings else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
