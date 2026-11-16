# 上传到 GitHub

当前已配置远端：`git@github.com:imyan521/PDA.git`，本地分支为 `main`，尚无首次提交。项目首页与忽略规则已准备。

本次执行环境无法解析 `github.com`，且 `.git` 为只读，因此尚未创建提交或推送。请在本机普通终端执行：

```bash
cd /home/cxz/PDA
git add .
git commit -m "Initial commit: PDA lidar mapping"
git push -u origin main
```

该操作使用现有 Git 配置和 SSH 身份。若提示 `Permission denied (publickey)`，需先为 GitHub 账户配置对应 SSH 公钥，不要将 SSH 私钥或访问令牌写进项目或仓库地址。

若提示仓库不存在，请确认 GitHub 上已创建 `imyan521/PDA` 且当前账户有写入权限；若远端已有提交而拒绝推送，应先获取并合并远端历史，不使用强制推送覆盖。

上传范围包括应用源码、资源、建图配置、预编译 native 库、Gradle 配置、测试、工具和说明文档。APK、构建缓存、现场录制 ZIP、分析数据、签名文件和本机硬件参考资料已排除。
