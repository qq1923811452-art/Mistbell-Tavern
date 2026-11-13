# Dev-only keystore

`mistbell-dev.jks` 只用于 CI 产出的**测试包**，让多次构建可以覆盖安装（否则每次随机 debug key，
用户每次都要卸载重装并清掉聊天记录）。

- alias：`mistbell-dev`
- store/key 密码：`mistbelldev`

**不要**用它签正式发布包；真要发布时换成独立 keystore 并放到仓库外。
