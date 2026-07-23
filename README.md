# WCX

一个集成了微信 Xposed 模块与去混淆分析工具的综合项目。

## 项目结构

```
wcx/
├── deobf/          # 微信模块去混淆分析工具 (unidbg 脱壳/解密)
├── app/            # Xposed 模块主程序
├── buildSrc/       # Gradle 构建配置
├── xtask/          # Rust 构建任务
├── docs/           # 文档
├── scripts/        # 辅助脚本
├── icons/          # 图标资源
└── libs/           # 依赖库
```

## 子项目

### deobf — 去混淆分析工具

基于 unidbg ARM64 模拟器的动态解密与去混淆框架，用于分析微信模块的加密载荷。

### app — Xposed 模块

适用于微信的 Xposed 模块，提供功能增强与定制化能力。

## 许可

- `deobf/` 目录下的代码基于 GPL-3.0 许可
- 其余代码基于相应许可协议