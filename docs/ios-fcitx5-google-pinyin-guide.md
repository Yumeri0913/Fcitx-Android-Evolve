# iOS Fcitx5 调教指南：向谷歌拼音靠拢

更新日期：2026-11-08。适用于 fcitx5-ios 开发者公测版（应用版本号 0.1.0，nightly 构建）。

目标是让 iPhone 上的 Fcitx5 打字手感接近老版安卓谷歌拼音 4.x：全拼、候选干净、标点顺手，并把谷歌拼音积累的用户词带过来。iOS 版能调的东西比安卓版少很多，下面每一项都注明了能不能做到。

> 标注说明：**【已核实】** 来自项目源码或文档；**【推断】** 是根据 fcitx5 通用约定推出来的，没有官方文档背书，操作前请先备份。

## 1. 安装与启用

1. **获取安装包。** iOS 版不在 App Store 上架。到 [nightly 发布页](https://github.com/fcitx-contrib/fcitx5-ios/releases/tag/latest) 下载 IPA，用 SideStore 安装。也可以在 AltStore / SideStore / Feather 里添加第三方源 `https://raw.githubusercontent.com/PapainTea/fcitx5-ios-source/main/source.json`。最低系统版本 iOS 16.3。【已核实】
2. **添加键盘。** 一个应用里带了四个键盘扩展：Chinese、Hallelujah（英文）、Mozc（日文）、Rime。到「设置 › 通用 › 键盘 › 键盘 › 添加新键盘」里，只添加 **Chinese** 即可。想用雾凇拼音再加 Rime，见第 6 节。【已核实】
3. **打开「完全访问」。** 键盘需要它来写共享目录、跳回主应用、联网和读剪贴板。【已核实】
4. **免费开发者账号侧载时要手动同步。** 免费账号没有 App Group，主应用改了配置或词库后，必须在主应用里点「同步配置」，并且同步时主应用要在前台。【已核实】

谷歌拼音没有独立的英文键盘，中英切换在同一个键盘里完成。iOS 版 Chinese 键盘里也可以只保留拼音，用系统地球键切到 iOS 自带英文键盘，或者长按地球键选输入法。

## 2. 输入法：只留全拼

谷歌拼音默认是全拼。Chinese 键盘默认同时启用了拼音、双拼和五笔拼音（wbx），切换时容易误入。【已核实】

在主应用「输入法」页面里，把 Chinese 键盘的输入法只留 **拼音**，删掉双拼和 wbx。如果你习惯双拼，就只留双拼，并在拼音设置里选对方案；谷歌拼音支持的方案是微软、紫光、拼音加加、智能 ABC、自然码、小鹤。

## 3. 拼音引擎设置

入口：主应用 › 输入法 › 拼音。这些是 fcitx5 标准选项，iOS 版都能在应用里改。【已核实】

| 选项 | 谷歌拼音的默认行为 | 建议值 | 说明 |
| --- | --- | --- | --- |
| 模糊音 | 总开关默认关 | 全部关 | 南方口音再按需打开，见下文 |
| 预测（联想） | 开 | 开；键盘常崩溃就关 | 预测模型占内存，见第 7 节 |
| 云拼音 | 谷歌拼音没有云输入 | 关 | 关掉还能省内存、不联网 |
| 显示英文候选 | 开，英文词会混进拼音候选 | 开 | 对应谷歌拼音的「中英混输」 |
| 学习 | 开 | 开 | 关掉就不会记住你造的词 |
| 拆字 | 无此功能 | 关 | 避免干扰 |
| 每页候选数 | 一行 6 个 | 不用改 | iOS 版候选栏横向滚动，此项基本无效 |

**模糊音怎么选。** 谷歌拼音打开模糊音总开关后，默认勾选的是 z/zh、c/ch、s/sh、an/ang、en/eng、in/ing 六组，l/n、f/h、r/l、ian/iang、uan/uang 默认不勾。想复刻这个效果，就在 fcitx 里只勾这六组。fcitx 提供的模糊音有 u↔v、an↔ang、en↔eng、ian↔iang、in↔ing、u↔ou、uan↔uang、c↔ch、f↔h、l↔n、l↔r、s↔sh、z↔zh。

**标点。** 「标点」子页面的映射编辑器可用。fcitx 默认中文状态下逗号、句号就是全角「，」「。」，与谷歌拼音一致，不用改。【已核实】

**简繁。** 谷歌拼音默认简体输出。保持「简繁转换」（chttrans）关闭即可。

## 4. 键盘外观与按键

这一部分 iOS 版能调的很少，先说结论：**主题、按键布局、键盘高度、按键音和振动都不能改。** 【已核实，依据源码；官方文档未提及】

- **主题。** 只跟随系统浅色/深色模式，配色写死在代码里，没有自定义主题或背景图。
- **布局。** 只有一套 26 键 QWERTY，没有九宫格。键盘高度和按键尺寸对齐 iOS 原生中文键盘。
- **按键音和振动。** 没有实现。
- **符号手势。** 与谷歌拼音相似但方向相反：谷歌拼音是**下滑**出按键下方的小符号，iOS 版是**上滑**或长按。首行上滑出数字 1 到 0；其他行例如 a→@、s→&*、d→=+、f→_-、h→%/、j→$#、k→{[(、l→}])、x→:、v→?、b→!、m→"。
- **其他手势。** 在空格上横滑移动光标，在删除键上横滑批量删除；这和谷歌拼音一致。长按地球键选输入法。
- **工具栏。** 有撤销、重做、光标编辑面板、剪贴板、状态菜单和收起键盘。状态菜单里的电源按钮可以重启键盘进程。

剪贴板设置在主应用「剪贴板」页：监听剪贴板、历史条数（默认 10）、剪贴板提示及超时、粘贴后回车、隐藏敏感内容、去除链接追踪参数。按个人习惯开关即可。

## 5. 词库：把谷歌拼音的用户词搬过来

iOS 版**没有应用内的词库导入界面**，「管理词库」「自定义短语」「快速输入」这几个入口在 iOS 上点了没反应，也不支持搜狗 scel 转换。【已核实】下面三条路线按推荐程度排列。

### 5.1 先从谷歌拼音导出

在装有谷歌拼音的安卓手机上：设置 › 词典 › 用户字典 › 导出用户字典。默认得到 `/sdcard/GooglePinyinInput/user-dictionary.txt`。

这个文件是带 BOM 的 UTF-16LE 文本，第一行是 `# User dictionary for Google Pinyin Input`，之后每行一个词，用制表符分隔「词、词频、空格分隔的拼音」，例如 `你好	5	ni hao`。英文词的拼音列带 `\0` 标记，fcitx 拼音用不上。

### 5.2 路线 A（推荐）：经安卓 Fcitx5（mod 分支）中转

本仓库 `mod` 分支的安卓版能直接读这个文件。

1. 在安卓 Fcitx5 里打开「拼音词典」，点右上角菜单「导入谷歌拼音用户词库」，选中 `user-dictionary.txt`。导入后会多出一个名为「谷歌拼音_user-dictionary」的词库，并提示导入和跳过的条数。
2. 安卓 Fcitx5 设置 › 高级 › 导出用户数据，得到一个 zip。iOS 版按包名 `org.fcitx.fcitx5.android` 识别安卓备份，请用正式版导出，debug 版的包名带 `.debug` 后缀。【推断】
3. 把 zip 传到 iPhone，在 iOS 版主应用「数据管理」里选「从 Fcitx5 Android 导入数据」。iOS 版的备份格式与安卓版一致，这是官方支持的迁移方式。【已核实】
4. 免费账号侧载的话，导入后点一次「同步配置」。

这条路线还会顺带迁移安卓上的其他设置、快速输入和剪贴板历史。

### 5.3 路线 B：用电脑转换后放进「文件」

需要一台装了 libime 工具（`libime_pinyindict`，Linux 发行版的 libime 包里有）的电脑。

1. 把谷歌拼音导出文件转成 libime 文本格式：

```python
# google2libime.py：谷歌拼音用户词库 -> libime 文本词库
import sys

raw = open(sys.argv[1], 'rb').read()
if raw.startswith(b'\xff\xfe'):
    text = raw[2:].decode('utf-16-le')
elif raw.startswith(b'\xef\xbb\xbf'):
    text = raw[3:].decode('utf-8')
else:
    text = raw.decode('gbk')

with open(sys.argv[2], 'w', encoding='utf-8') as out:
    for line in text.splitlines():
        if not line.strip() or line.startswith(('#', ';')):
            continue
        fields = line.split('\t')
        if len(fields) < 3 or '\\' in fields[2]:
            continue  # 跳过英文词
        word, pinyin = fields[0], fields[2].split()
        pinyin = ['lve' if p == 'lue' else 'nve' if p == 'nue' else p.replace('ü', 'v')
                  for p in pinyin]
        if len(word) == len(pinyin):
            out.write(word + ' ' + "'".join(pinyin) + ' 0\n')
```

```sh
python3 google2libime.py user-dictionary.txt google.txt
libime_pinyindict google.txt 谷歌拼音.dict
```

2. 用隔空投送或「文件」把 `谷歌拼音.dict` 拷到「我的 iPhone › Fcitx5 › data › pinyin › dictionaries」。目录不存在就新建。【推断：路径按 fcitx5 约定，iOS 版的数据目录与安卓版结构相同】
3. 回到 Fcitx5 主应用让它同步；免费账号侧载要点「同步配置」。下次弹出键盘时生效。

### 5.4 备份与导出

主应用「数据管理」里可以导出为 Fcitx5 Android/iOS/macOS 通用的 zip。【已核实】

注意：免费账号侧载时，键盘里学到的新词存在键盘自己的容器里，同步功能故意不回传 `user.dict`，所以从主应用导出的备份**可能不含**你在 iPhone 上新学的词。【推断】想把 iPhone 上的词再导回谷歌拼音，目前没有可靠路径。

## 6. 备选：Rime（雾凇拼音）

如果内置拼音的词频不合口味，可以试 Rime 键盘。nightly 构建的 Rime 键盘预装了雾凇拼音（rime-ice）完整方案。【已核实】

- **用户目录。** `Documents/data/rime/`。在 Rime 插件设置里点「用户数据目录」会直接用「文件」打开它。
- **加方案。** 把方案文件放进上面的目录，或在「数据管理」里导入仓输入法（Hamster）的备份 zip。
- **部署。** 在键盘状态菜单里点「部署」，它会跳到主应用执行部署。键盘本身启动时不部署，以节省内存。

Rime 方案和词典越大，键盘越容易因内存不足被系统杀掉。【推断】

## 7. 已知限制与排错

- **内存上限。** iOS 键盘扩展大约只有 77 MB 内存，超出会被系统直接杀掉。开发者实测 Chinese 键盘冷启动约 50 MB，首次输入后 66 到 77 MB。【已核实】键盘经常闪退时，依次尝试：关预测、关云拼音、移除大型附加词库。
- **改了设置不生效。** 免费账号侧载时，回主应用点「同步配置」，并保持主应用在前台直到同步完成。然后收起键盘再重新弹出。
- **键盘卡死或异常。** 用状态菜单里的电源按钮重启键盘进程，或在系统设置里关掉再打开「完全访问」。
- **删掉的文件还在生效。** 有 App Group 时，从 Documents 删除的文件不会同步删除共享目录里的副本。【已核实】
- **崩溃日志。** 在「设置 › 隐私与安全性 › 分析与改进 › 分析数据」里找。
- **反馈渠道。** 项目关闭了 GitHub Issues，反馈请去 [Telegram 群](https://t.me/fcitx5macos/16126)。

## 8. 与谷歌拼音的差异速查

| 谷歌拼音 4.x | iOS Fcitx5 | 能否对齐 |
| --- | --- | --- |
| 下滑出按键小符号 | 上滑或长按出符号 | 不能改方向 |
| 顶栏「中/英」标签切换 | 地球键或系统键盘切换 | 部分 |
| 九宫格、手写、笔画 | 只有 26 键 | 不能 |
| 浅色 Material 主题 | 跟随系统浅色/深色 | 不能自定义 |
| 按键振动开、声音关 | 无振动无声音 | 不能 |
| 用户词库导入导出 | 经安卓版或电脑中转导入 | 部分 |
| 模糊音、联想、中英混输 | 同名选项可调 | 能 |

## 资料来源

- [fcitx5-ios 仓库与 README](https://github.com/fcitx-contrib/fcitx5-ios)
- [nightly 发布页](https://github.com/fcitx-contrib/fcitx5-ios/releases/tag/latest)
- [完全访问说明](https://github.com/fcitx-contrib/fcitx5-ios/blob/master/docs/full-access.md)
- [配置同步机制](https://github.com/fcitx-contrib/fcitx5-ios/blob/master/docs/synchronization.md)
- [第三方侧载源](https://github.com/PapainTea/fcitx5-ios-source)
- [键盘布局定义](https://github.com/fcitx-contrib/fcitx5-keyboard-layouts)
- 谷歌拼音 4.5.2 的默认值、配色和词库格式：对其安卓安装包的静态分析
