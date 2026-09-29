# Day 05 · HDFS 实操与运维速查卡

> **今日目标**：把 HDFS 日常最常用的 20 条命令练到"不用查文档"；搞懂权限、配额、快照、回收站四个运维机制（都有生活类比）；产出一张《HDFS 运维速查卡》——以后写数仓任务时随手查。
> **时长**：命令实操 2h / 运维机制 1.5h
> **今日产出**：《HDFS 速查卡》（docs/hadoop/hdfs-cheatsheet.md）+ 权限/快照/回收站实验

## 1. 知识地图

```
HDFS 命令的心智模型：把 hdfs dfs -xxx 和你天天在用的 Linux 命令对上号，
一天就记完——真正的差异点只有四个（路径前缀、权限模型、快照、回收站）：

常用命令（Linux 对照记忆法）：
  -put / -copyFromLocal   = 上传（cp 到 HDFS）
  -get / -copyToLocal     = 下载
  -ls / -cat / -tail      = 看目录/看文件/看末尾
  -mkdir -p               = 建目录（-p 递归，同 Linux）
  -mv / -cp / -rm -r      = 移动/复制/删除
  -du -h                  = 看目录占用
  -getmerge               = 合并下载（day04 治小文件用过）
  -chmod / -chown         = 改权限/改属主
独有命令（Linux 没有，HDFS 特色）：
  -setrep 3 /path         = 改副本数（HDFS 才有"副本"概念）
  -setfacl / -getfacl     = 细粒度权限（默认权限模型不够用时）
  fsck /path -files -blocks = 体检（看文件被切成哪些块、副本齐不齐）
  dfsadmin -report        = 集群体检（容量/存活节点/坏块）
  dfsadmin -metasave      = 导出元数据统计（day04 用过）
  dfsadmin -safemode get  = 看安全模式状态（见下）

四个运维机制（每个先记类比再记命令）：
① 权限 Permission——和 Linux 几乎一样（rwx + 属主/组/其他），
  但注意：HDFS 默认"不严格"，很多部署直接关了权限检查（dfs.permissions=false）
  ——生产必须开，否则谁都能删数据（真实事故案例一抓一把）。
  （类比：仓库门禁。默认全公司都能进，生产环境必须刷卡。）
② 配额 Quota——给目录限"文件个数"或"磁盘字节"，
  防止某个新人的任务把集群写爆（空间配额 name quota 空间配额 space quota）。
  （类比：给每个租户限电。）
③ 快照 Snapshot——给目录拍"只读快照"，误删后能恢复。
  不是备份全量数据（HDFS 块不复制，只记录差异指针），所以几乎零成本。
  （类比：存档点。游戏打 Boss 前先存档。）
④ 回收站 Trash——-rm 删的文件进 /user/root/.Trash/，默认留 6 小时
  （fs.trash.interval=360），期间可捞回来；超时真删。
  （类比：Windows 回收站。知道这个机制，误删就不会慌。）
⚠ 安全模式 SafeMode：NameNode 启动时的"自检模式"（检查块报告、副本达标率），
  期间只读不写。数据丢失率高时集群会卡在安全模式——运维常见故障之一，
  手动退出：hdfs dfsadmin -safemode leave（先确认副本健康再退！）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Permission | 权限（类 Linux 的 rwx 模型，生产必须开启） |
| Quota | 配额（目录级的"限流"，防写爆集群） |
| Snapshot | 快照（目录存档点，误删恢复用，几乎零成本） |
| Trash | 回收站（删除先进站，默认 6 小时可捞回） |
| SafeMode | 安全模式（NameNode 启动自检期，只读不写） |
| fsck | 文件系统体检（fsck = file system check） |

## 3. 动手实操：四大机制逐个验证

```powershell
# ① 权限实验：开权限的集群上，别人目录不能随便进
docker exec hadoop-single hdfs dfs -mkdir /private-zhang
docker exec hadoop-single hdfs dfs -chmod 700 /private-zhang
docker exec hadoop-single hdfs dfs -ls / | Select-String "private"   # 属主 root 权限 700

# ② 配额实验：给目录限 3 个文件，第 4 个写入报错
docker exec hadoop-single hdfs dfs -admin -setQuota 3 /smallfiles-merged
docker exec hadoop-single bash -c "echo a > /tmp/a.txt; hdfs dfs -put /tmp/a.txt /smallfiles-merged/"
# 第 4 次上传会报 The NameSpace quota ... is exceeded —— 配额生效
docker exec hadoop-single hdfs dfs -admin -clrQuota /smallfiles-merged   # 解除

# ③ 快照实验：拍快照 → 删文件 → 从快照捞回
docker exec hadoop-single hdfs dfsadmin -allowSnapshot /smallfiles-merged
docker exec hadoop-single hdfs dfs -createSnapshot /smallfiles-merged snap1
docker exec hadoop-single hdfs dfs -rm /smallfiles-merged/merged.txt
docker exec hadoop-single hdfs dfs -cp /smallfiles-merged/.snapshot/snap1/merged.txt /smallfiles-merged/
docker exec hadoop-single hdfs dfs -ls /smallfiles-merged   # 文件回来了

# ④ 回收站实验：删除后找到"尸体"
docker exec hadoop-single hdfs dfs -rm /smallfiles-merged/merged.txt
docker exec hadoop-single hdfs dfs -ls /user/root/.Trash/Current/smallfiles-merged/
# 想立刻真删（确定不要了）：hdfs dfs -expunge

# ⑤ 体检三连（速查卡核心内容）
docker exec hadoop-single hdfs fsck / -files -blocks -locations | Select-Object -First 20
docker exec hadoop-single hdfs dfsadmin -report | Select-Object -First 15
docker exec hadoop-single hdfs dfsadmin -safemode get
```

```markdown
<!-- docs/hadoop/hdfs-cheatsheet.md —— 《HDFS 运维速查卡》骨架（自己补全 20 条） -->
## 日常文件操作（8 条）：put/get/ls/mkdir -p/mv/cp/rm -r/getmerge
## 集群健康（4 条）：dfsadmin -report / fsck / safemode get / metasave
## 权限与配额（4 条）：chmod/chown/setQuota+clrQuota/setfacl
## 安全网（4 条）：createSnapshot/删除快照/Trash 路径/expunge
每条记三列：命令 | 干什么 | 什么时候用（举例）
—— 排查事故的顺序固定：report 看集群 → fsck 看文件 → safemode 看启动 → Trash 找数据
```

## 4. 面试连接

**Q：线上 HDFS 误删了数据，怎么救？**
> 三道安全网按顺序捞。第一道回收站：rm 删的文件默认在 /user/{user}/.Trash/Current 下保留 6 小时（fs.trash.interval 控制），直接 -cp 或 -mv 捞回原位就行——所以发现误删第一动作是"停止继续删"，别手快再 expunge。第二道快照：如果目录开过 snapshot，任何时间点都能从 .snapshot/snapX 目录把文件复制回来，快照只记差异指针几乎零成本，重要目录（数仓 ODS 入口）应该常态开。第三道副本自愈兜不了"逻辑删除"，但能兜"单块损坏"：fsck 检查到坏块，NameNode 会自动用健康副本补齐。如果三道都漏了——那就只能从上游重新同步数据，这也是为什么数仓分层（day15）重要：ODS 丢了可以从业务库重拉，DWD 丢了可以从 ODS 重算——每一层都是上一层的"逻辑备份"。我们规范里因此有两条硬规则：rm 一律带 -skipTrash 禁令（防手快）+ ODS 目录强制开快照。

**Q：HDFS 的权限和 Linux 权限有什么区别？**
> 模型基本一样（rwx、属主/组/其他、chmod/chown 都通用），差别在三点：一是 HDFS 默认很多发行配置关了权限检查（dfs.permissions=false），生产必须显式打开，这是常见的安全事故源头；二是 HDFS 没有超级用户 root 的概念，NameNode 进程的启动用户就是"超级用户"，权限管理要先想清楚"谁启动的服务"；三是细粒度场景用 ACL（setfacl）补默认模型不够用的地方——比如"某目录 A 组可读写、B 组只读、C 用户单独可写"，默认三段 rwx 表达不了就要 ACL。最后补一个运维常识：HDFS 权限管的是"访问元数据的检查"，不加密数据本身，真正的数据安全还要靠 Kerberos 认证和传输加密——权限是门禁卡，不是保险柜。

## 5. 今日验收清单

- [ ] 20 条命令速查卡完成（四类分组+使用场景列）
- [ ] 配额/快照/回收站三个实验全跑通（有输出截图或记录）
- [ ] 安全模式是什么、为什么卡、怎么处理能讲清
- [ ] 误删救数据"三道安全网"顺序能脱口而出
- [ ] `git add . && git commit -m "day10-05: hdfs ops"`

---
[← Day 04](day04-HDFS高可用与小文件治理.md) | [本月目录](README.md) | [Day 06 · MapReduce 与 Shuffle →](day06-MapReduce与Shuffle.md)
