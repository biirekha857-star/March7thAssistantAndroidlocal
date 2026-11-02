@echo off
chcp 65001 >nul
rem 一条命令同步到 Gitee 私有仓库（Windows 版，双击也行）。
rem
rem 用法：
rem   scripts\sync.bat "改了任务包的参数表单"
rem   scripts\sync.bat            （不写说明就用 sync）
rem
rem 双击运行的话，出错时窗口会一闪而过看不到原因。建议在项目根目录的
rem 命令行里敲，或者右键「在终端中打开」。

setlocal
cd /d "%~dp0.."

if "%~1"=="" (set "MSG=sync") else (set "MSG=%~1")

echo ==^> 拉取 Gitee 上的改动
git pull --rebase --autostash gitee main
if errorlevel 1 goto fail

echo ==^> 暂存所有改动
git add -A

git diff --cached --quiet
if errorlevel 1 (
    echo ==^> 提交：%MSG%
    git commit -m "%MSG%"
    if errorlevel 1 goto fail
) else (
    echo ==^> 没有新改动，只需要推送
)

echo ==^> 推送到 gitee/main
git push gitee main
if errorlevel 1 goto fail

echo ==^> 完成
endlocal
exit /b 0

:fail
echo.
echo ==^> 同步失败。常见原因：
echo     1. 没网 / 代理挂了
echo     2. 和别人改了同一行，rebase 冲突（按提示改完，git rebase --continue）
echo     3. 凭据过期（重新配置 ~/.git-credentials 或换 SSH）
endlocal
exit /b 1
