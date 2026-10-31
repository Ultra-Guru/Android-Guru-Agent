<!-- pr: getsystemservice-safe-cast -->

- 修复 6 处系统服务不安全强转：手电筒/网络信息/保险库粘贴/终端历史复制/电池豁免在极端 ROM（getSystemService 返回 null）下不再崩溃，统一降级为明确报文或跳过
