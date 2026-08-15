# 学习桌面系统策略协议

学习桌面本身不直接写 `Settings.Global.policy_control`，也不直接停用 Taskbar
或修改厂商导航设置。普通 Activity 直接隐藏系统栏会在部分掌阅固件上残留到后续
应用，因此 `StudyHomeActivity` 始终保持系统栏和全面屏手势可用。

需要限制最近任务或第三方 Taskbar 时，由 OceanMultiTask 的 SystemUI 进程执行。
学习桌面只发送显式广播：

```text
action: com.openai.oceanmultitask.action.RECONCILE_STUDY_DESKTOP_POLICY
package: com.android.systemui
schema_version: 1
directive: ENFORCE | RESTORE
requested_active: true | false
study_package: 实际包名（正式版或 .debug）
study_mode: ACTIVE | ENABLING | EXITING | RECOVERY | DISABLED
default_home: true | false
reason: 状态或生命周期原因
sent_elapsed_realtime: SystemClock.elapsedRealtime()
```

## SystemUI 端必须遵守的状态机

广播只是唤醒信号，不能作为授权事实。每次执行前都必须独立解析当前默认 HOME，
并验证它确实是 `study_package` 的 HOME Activity。

| 当前事实 | 动作 |
| --- | --- |
| `ACTIVE` 且学习桌面是默认 HOME | 首次原子保存原状态，再应用限制 |
| 默认 HOME 已变化 | 立即原样恢复并清除本次会话快照 |
| `EXITING`、`RECOVERY`、`DISABLED`、`ENABLING` | 恢复 |
| 广播缺失、字段无效、包名不受信任、HOME 查询失败 | 恢复（fail-open） |

原状态快照必须区分“不存在/空字符串/具体值”，至少覆盖：

- `Settings.Global.policy_control` 的精确可空值；
- Taskbar 原先是否启用、是否具有悬浮窗权限、服务是否在运行；
- 所有确实被修改的厂商导航设置的精确可空值。

同一会话只能保存一次基线，不能把已经限制后的值覆盖成新基线。恢复必须幂等：
先写回所有基线，再校验读回值，全部成功后才删除快照。

SystemUI 启动、用户解锁、HOME/角色变化、Taskbar 启动和最近任务手势入口都应主动
重新核对默认 HOME，不能依赖学习桌面的进程或无障碍服务仍然存活。SystemUI 若崩溃，
重启后从持久化快照恢复或重新进入限制；学习桌面的前台服务同时每两秒进行一次
HOME 兜底核对，并每五秒以内补发一次状态心跳。

限制应针对“绕过入口”，不要关闭 Android 的返回手势：

- HOME 保持可用，它本来就会回到学习桌面；
- 屏蔽或过滤最近任务入口；
- 隐藏并阻止第三方 Taskbar 打开未授权应用；
- 返回手势和学习白名单应用内部触控保持可用。
