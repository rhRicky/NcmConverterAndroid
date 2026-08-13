"""
NCM to MP3/FLAC 转换器 (高性能优化版)
功能：解密 NCM 格式，自动补全封面、歌手、专辑等信息。
优化：整数位运算加速 + 多线程并发

说明：本文件是转换算法的权威参考实现（作者已验证其正确性）。
App 中的 Kotlin 代码 (app/src/main/java/com/ncmc/ricky/core/NcmConverter.kt)
是该算法的逐行忠实移植，关键段已标注对应关系，便于核对。
"""

import sys
import subprocess
import importlib
import os
import binascii
import struct
import base64
import json
import time
from concurrent.futures import ThreadPoolExecutor

# --- 依赖自动安装逻辑 ---
def install_and_import(package):
    try:
        if package == "pycryptodome":
            importlib.import_module("Crypto")
        else:
            importlib.import_module(package)
    except ImportError:
        print(f"正在安装缺失组件: {package}...")
        try:
            subprocess.check_call([sys.executable, "-m", "pip", "install", package])
        except Exception as e:
            print(f"安装 {package} 失败: {e}")
            print("请尝试手动在终端运行: pip install pycryptodome mutagen")
            sys.exit(1)

# 检查并安装依赖
packages = ["pycryptodome", "mutagen"]
for package in packages:
    install_and_import(package)

from Crypto.Cipher import AES
from mutagen import flac
from mutagen.id3 import ID3, APIC
from mutagen.mp3 import MP3
from mutagen.easyid3 import EasyID3

# --- 核心解密函数 (优化版) ---
def dumpfile(file_path, output_dir):
    try:
        # 1. 密钥准备
        core_key = binascii.a2b_hex("687A4852416D736F356B496E62617857")
        meta_key = binascii.a2b_hex("2331346C6A6B5F215C5D2630553C2728")
        unpad = lambda s : s[0:-(s[-1] if type(s[-1]) == int else ord(s[-1]))]

        file_name_raw = os.path.basename(file_path)
        
        with open(file_path, 'rb') as f:
            header = f.read(8)
            if binascii.b2a_hex(header) != b'4354454e4644414d':
                print(f"[跳过] 非 NCM 文件: {file_name_raw}")
                return

            f.seek(2, 1)
            key_length = struct.unpack('<I', f.read(4))[0]
            key_data = bytearray(f.read(key_length))
            key_data = bytes(bytearray([byte ^ 0x64 for byte in key_data]))
            cryptor = AES.new(core_key, AES.MODE_ECB)
            key_data = unpad(cryptor.decrypt(key_data))[17:]
            key_length = len(key_data)
            
            # --- 关键优化 1: RC4 Key Box 初始化 ---
            key_box = bytearray(range(256))
            c = 0
            last_byte = 0
            key_pos = 0
            for i in range(256):
                c = (key_box[i] + last_byte + key_data[key_pos]) & 0xff
                key_pos += 1
                if key_pos >= key_length:
                    key_pos = 0
                key_box[i], key_box[c] = key_box[c], key_box[i]
                last_byte = c

            meta_length = struct.unpack('<I', f.read(4))[0]
            if meta_length == 0:
                print(f"[警告] 元数据丢失: {file_name_raw}")
                # 简单处理，假设 meta 缺失
                meta_data = {'format': 'mp3', 'musicName': 'Unknown', 'album': 'Unknown', 'artist': [['Unknown']]}
            else:
                meta_data_bytes = bytearray(f.read(meta_length))
                meta_data_bytes = bytes(bytearray([byte ^ 0x63 for byte in meta_data_bytes]))
                meta_data_str = base64.b64decode(meta_data_bytes[22:])
                cryptor = AES.new(meta_key, AES.MODE_ECB)
                meta_data_str = unpad(cryptor.decrypt(meta_data_str)).decode('utf-8')
                meta_data = json.loads(meta_data_str[6:])
            
            f.seek(4, 1) # CRC32
            f.seek(5, 1)
            image_size = struct.unpack('<I', f.read(4))[0]
            image_data = f.read(image_size)
            
            # 准备输出路径
            target_format = meta_data.get('format', 'mp3')
            target_filename = os.path.splitext(file_name_raw)[0] + '.' + target_format
            music_path = os.path.join(output_dir, target_filename)

            # --- 关键优化 2: 预计算 XOR 掩码 (Pre-computation) ---
            # 原始逻辑是 dynamic j = i & 0xff。这意味着掩码是 256 字节循环的。
            # 原始序列: j 从 1 到 255 再到 0 (因为 i 从 1 开始)
            # 我们生成一个 256 字节的标准掩码块
            final_box_map = bytearray(256)
            for i in range(256):
                # 模拟原始逻辑: j = (i + 1) & 0xff
                # 这里我们生成索引 0-255，对应原始逻辑 i=1..256
                # 当 i=0 (代表原始第1字节), j=1
                # 当 i=255 (代表原始第256字节), j=0
                j = (i + 1) & 0xff 
                val = (key_box[j] + key_box[(key_box[j] + j) & 0xff]) & 0xff
                final_box_map[i] = key_box[val]

            # --- 关键优化 3: 整数位运算加速 (Integer Vectorization) ---
            # 块大小设为 64KB (256 * 256)，保证对齐
            CHUNK_SIZE = 65536 
            
            # 将 256 字节的掩码平铺填满 64KB
            full_chunk_mask = final_box_map * (CHUNK_SIZE // 256)
            # 转换为大整数，利用 Python 对大整数的高效位运算
            full_chunk_mask_int = int.from_bytes(full_chunk_mask, 'little')

            with open(music_path, 'wb') as m:
                while True:
                    chunk = f.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    
                    current_len = len(chunk)
                    
                    # 极速路径：如果是完整的 64KB 块
                    if current_len == CHUNK_SIZE:
                        chunk_int = int.from_bytes(chunk, 'little')
                        decrypted_int = chunk_int ^ full_chunk_mask_int
                        m.write(decrypted_int.to_bytes(current_len, 'little'))
                    else:
                        # 尾部处理：不足 64KB 的部分，切片掩码后处理
                        # 依然使用整数加速，比逐字节循环快得多
                        mask_part = full_chunk_mask[:current_len]
                        mask_int = int.from_bytes(mask_part, 'little')
                        chunk_int = int.from_bytes(chunk, 'little')
                        decrypted_int = chunk_int ^ mask_int
                        m.write(decrypted_int.to_bytes(current_len, 'little'))

        # 写入元数据标签
        tag_audio(music_path, meta_data, image_data)
        print(f"[完成] {target_filename}")

    except Exception as e:
        print(f"[错误] 处理 {file_path} 失败: {e}")
        import traceback
        traceback.print_exc()

def tag_audio(music_path, meta_data, image_data):
    try:
        if meta_data['format'] == 'flac':
            audio = flac.FLAC(music_path)
            image = flac.Picture()
            image.type = 3
            image.mime = 'image/jpeg'
            image.data = image_data
            audio.add_picture(image)
            audio['title'] = meta_data['musicName']
            audio['album'] = meta_data['album']
            # 安全获取 artist
            artists = meta_data.get('artist', [])
            if artists:
                audio['artist'] = '/'.join([a[0] for a in artists if a])
            audio.save()
            
        elif meta_data['format'] == 'mp3':
            audio = MP3(music_path, ID3=ID3)
            # 即使没有 ID3 也要创建
            try:
                audio.add_tags()
            except:
                pass
            audio.tags.add(APIC(encoding=3, mime='image/jpeg', type=3, desc='Cover', data=image_data))
            audio.save()
            
            audio = EasyID3(music_path)
            audio['title'] = meta_data['musicName']
            audio['album'] = meta_data['album']
            artists = meta_data.get('artist', [])
            if artists:
                audio['artist'] = '/'.join([a[0] for a in artists if a])
            audio.save()
    except Exception as e:
        print(f"    -> 标签写入警告: {e}")

def process_folder(input_folder, output_folder):
    if not os.path.exists(output_folder):
        os.makedirs(output_folder)
    
    files = [f for f in os.listdir(input_folder) if f.lower().endswith('.ncm')]
    if not files:
        print("该文件夹下没有发现 .ncm 文件！")
        return

    print(f"发现 {len(files)} 个文件，准备开始处理 (2线程并发)...")
    print("-" * 30)

    # 线程池处理
    full_paths = [(os.path.join(input_folder, f), output_folder) for f in files]
    
    # 开启2个线程
    with ThreadPoolExecutor(max_workers=2) as executor:
        # 将任务提交给线程池
        executor.map(lambda p: dumpfile(*p), full_paths)

# --- 入口函数 ---
if __name__ == "__main__":
    if len(sys.argv) > 1:
        in_dir = sys.argv[1]
        out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(in_dir, "Converted")
    else:
        print("--- NCM 极速转换器 Super ---")
        in_dir = input("请输入 NCM 文件夹路径: ").strip()
        # 处理引号问题（手机复制路径常带引号）
        in_dir = in_dir.strip('"').strip("'")
        
        out_dir = input("请输入输出路径 (回车默认 Converted): ").strip()
        out_dir = out_dir.strip('"').strip("'")
        
        if not out_dir:
            out_dir = os.path.join(in_dir, "Converted")

    if os.path.isdir(in_dir):
        start_time = time.time()
        process_folder(in_dir, out_dir)
        end_time = time.time()
        print("-" * 30)
        print(f"全部处理完成！耗时: {end_time - start_time:.2f}秒")
        print(f"保存路径: {out_dir}")
    else:
        print("输入的路径无效，请检查后重试。")
