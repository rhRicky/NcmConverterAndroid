# NCM Converter Android

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-orange.svg)](https://kotlinlang.org/)


一款将网易云音乐加密格式 NCM 转换为标准 MP3/FLAC 的 Android 应用。让无数的歌手破防的一个项目！😄


一款将网易云音乐加密格式 NCM 转换为标准 MP3/FLAC 的 Android 应用。


## ✨ 功能特性

- **批量转换**：支持将整个目录下的所有 `.ncm` 文件批量转换为 MP3 或 FLAC
- **元数据补全**：自动提取并写入歌曲名、歌手、专辑、封面图等标签信息
- **2线程并发**：并发处理提升转换速度
- **实时进度**：显示转换进度、速度、队列状态
- **取消支持**：转换过程中可随时取消，自动清理已生成文件
- **兼容性好**：适配 Android 12+ 作用域存储限制

## 🔧 技术实现

### 核心解密算法

NCM 是网易云音乐自定义的加密音频格式，文件头魔数为 `CTENFDAM`。

本项目基于官方参考实现 `ncm_decrypt_reference.py` 进行逐行移植，包含：

| 步骤 | 说明 |
|------|------|
| **密钥准备** | AES-128-ECB 解密 RC4 密钥（核心密钥：`687A4852416D736F356B496E62617857`） |
| **RC4 Key Box** | 基于解密密钥初始化 256 字节置换表 |
| **元数据解密** | AES-ECB 解密 base64 编码的 JSON 元数据（元数据密钥：`2331346C6A6B5F215C5D2630553C2728`） |
| **XOR 掩码** | 预计算 256 字节循环掩码，分块异或解密音频流 |
| **标签写入** | MP3 → ID3v2，FLAC → Vorbis Comment |

### 代码结构

```
app/src/main/java/com/ncmc/ricky/
├── core/
│   ├── NcmConverter.kt       # 核心解密逻辑（逐行移植自 Python 参考）
│   ├── ConversionEngine.kt   # 并发引擎 + 进度管理
│   ├── Id3v2Writer.kt        # MP3 标签写入
│   ├── FlacTagWriter.kt      # FLAC 标签写入
│   └── JpegParser.kt         # 封面图片解析
├── model/
│   └── ConversionItem.kt     # 转换项数据模型
├── adapter/
│   └── ConversionAdapter.kt  # 列表适配器
├── util/
│   └── StorageUtil.kt        # 存储工具类
├── MainActivity.kt           # 主界面
├── NcmConversionService.kt   # 前台服务
└── FolderPickerActivity.kt   # 目录选择器
```

## 📦 构建与安装

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17+
- Android SDK 31+

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/rhRicky/NcmConverterAndroid.git
cd NcmConverterAndroid

# 2. 使用 Gradle 构建 Debug APK
./gradlew assembleDebug

# 3. 输出位置
app/build/outputs/apk/debug/app-debug.apk
```

### 手动转换（备用）

如需在电脑上批量转换，可使用参考脚本：

```bash
python ncm_decrypt_reference.py <输入目录> [输出目录]
```

依赖自动安装：
```bash
pip install pycryptodome mutagen
```

## 📋 使用说明

1. 打开应用，授予存储权限
2. 点击「输入目录」选择包含 NCM 文件的文件夹
3. 点击「输出目录」选择转换结果保存位置
4. 点击「开始转换」启动批量转换
5. 转换过程中可实时查看进度，支持取消

## ⚠️ 免责声明

- 本工具仅供学习研究及个人备份使用
- 请确保您拥有相关音乐作品的合法使用权
- 作者不对使用本工具产生的任何后果负责
- 请勿用于商业目的或传播受版权保护的內容

## 📄 License

MIT License

## 🔗 相关链接

- [网易云音乐 NCM 格式](https://music.163.com/)
- [Python 参考实现](ncm_decrypt_reference.py)
- [AES 加密标准](https://en.wikipedia.org/wiki/AES_algorithm)
- [RC4 流密码](https://en.wikipedia.org/wiki/RC4)
