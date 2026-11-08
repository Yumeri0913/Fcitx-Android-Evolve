# iOS 方案：谷歌拼音用户词库并入 Fcitx 用户词库

更新日期：2026-11-08。对应安卓 `mod` 分支提交 `c0a269b7` 和 `4ae78b5e`。

本文给 fcitx5-ios 开发者使用。目标是让 iOS 与安卓行为一致：导入的谷歌拼音词和 Fcitx 自己学到的词完全一样，会随日常输入继续学习。下文所有 fcitx5-ios 代码位置都按 2026-11-08 的 `master` 核对过；标「需核实」的是没有在设备上验证的推断。

## 1. 要达到的行为

导入、学习、导出三步，iOS 与安卓必须一致。

| 行为 | 要求 |
| --- | --- |
| 导入 | 谷歌词写进拼音 `user.dict`，与 Fcitx 学到的词放在一起，不再生成附加词库 |
| 初始排序 | 谷歌词频写进 `user.history`，导入后常用词立刻排在前面 |
| 日常学习 | 选词照常更新 `user.history`，用得多的词往前排 |
| 删除 | 候选菜单的「忘记候选」能删掉导入的词 |
| 导出 | 导出 `user.dict` 里的全部词，词频取自 `user.history` 中的实际使用次数 |
| 不丢数据 | 学到的词在键盘收起后尽快落盘，不依赖 30 分钟一次的自动保存 |

安卓端已在 Android 16 模拟器上用设备端测试验证了上表的前五项，见 `app/src/androidTest/.../GooglePinyinUserDataTest.kt`。

## 2. 两端共用的部分

### 2.1 C++ 核心：直接复制

`app/src/main/cpp/googlepinyin/userdata.h` 和 `userdata.cpp` 只依赖 libime 和 fcitx5-utils，没有任何安卓代码，原样复制到 fcitx5-ios 即可。

```cpp
namespace googlepinyin {
struct UserWord { std::string word; std::string pinyin; int32_t count = 1; };
struct MergeResult { size_t added, existing, invalid; };

// 把词加进 user.dict，并按词频把使用记录写进 user.history；文件原子替换
MergeResult mergeIntoUserData(const std::string &userDictPath,
                              const std::string &historyPath,
                              std::vector<UserWord> words);
// 读出词库里的全部词；count 取自 history 中的原始使用次数，至少为 1
std::vector<UserWord> readUserWords(const std::string &dictPath,
                                    const std::string &historyPath);
std::string toTsv(const std::vector<UserWord> &);   // "词\t拼音\t次数\n"
std::vector<UserWord> fromTsv(std::string_view);
}
```

关键规则都在这两个文件里，iOS 不要另写：

- **拼音编码。** 写进历史的「编码」与 libime `PinyinContext::learn` 一致，用 `encodeFullPinyinWithFlags(pinyin, VE_UE)`。编码不同的话，历史记录对不上候选，排序不会生效。
- **词频换算。** 每个词写入历史 1 + floor(log2(词频)) 次，最多 5 次。按词频从低到高写入，最常用的词成为最新的历史。历史池只有 128、8192、65536 句三档，取对数是为了保留谷歌的相对顺序，又不把用户原有的历史挤掉。
- **重复导入。** 词已在 `user.dict` 里时只追加历史。合并本身不是幂等的，重复应用同一份导入会让词频翻倍，iOS 需要自己记录已应用的导入，见第 4 节。

调用前提是**拼音引擎没有持有这两个文件**：引擎只在启动时读取，保存时整个覆盖。安卓的做法是保存后停掉整个 Fcitx，改完再启动。iOS 的做法见第 4 节。

### 2.2 谷歌拼音文件格式：按参考实现移植

解析谷歌文件需要处理 GBK，放在平台层更方便，iOS 用 Swift 实现。参考实现是 `app/src/main/java/.../data/pinyin/dict/GooglePinyinUserDict.kt`，测试用例在 `app/src/test/.../GooglePinyinUserDictTest.kt`，请把这 7 个用例原样移植成 Swift 测试。

- **谷歌导出的格式。** UTF-16LE 带 BOM，换行 `\n`。首行 `# User dictionary for Google Pinyin Input`，之后每行 `词<TAB>词频<TAB>空格分隔的拼音`，可能还有 `<TAB>tx=N`。英文词的拼音列带 `\0` 这类语言标记。
- **解码。** 按 BOM 选 UTF-8、UTF-16LE 或 UTF-16BE。没有 BOM 时先按 UTF-8 严格解码，失败再用 GB18030（Swift 里是 `String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))`）。
- **逐行解析。** 跳过空行和 `#`、`;` 开头的行。行内有 TAB 就按 TAB 切，否则按单个空格切。前三列顺序不限，逐列判断：含汉字的是词；匹配 `^'?[a-zü]+([ '|][a-zü]+)*'?$` 的是拼音；匹配 `^[0-9]+,?$` 的是词频，最小取 1。含语言标记或纯英文单词的整行跳过。
- **拼音规范化。** `ü` 写成 `v`，`lue`、`nue` 写成 `lve`、`nve`。音节必须在 libime 的 422 个合法全拼音节内，表就在 Kotlin 文件的 `VALID_SYLLABLES` 里。音节数必须等于汉字数，扩展 B 区汉字按码点计数。
- **去重。** 同一个「词＋拼音」只保留一条，词频取较大值。
- **写回谷歌格式。** UTF-16LE 带 BOM，首行同上，每行 `词<TAB>词频<TAB>空格分隔的拼音`。

## 3. fcitx5-ios 现状中与本功能相关的发现

以下几条是读源码得出的，前三条会直接影响「像谷歌拼音一样学习」，建议先修。

1. **键盘从不主动保存学到的词。** 在 fcitx5-ios 源码里找不到 `instance->save()` 调用，只有 `common/runtime_main.cpp` 第 32 行的 `imMgr.save()`。学到的词只靠 Fcitx 默认 30 分钟一次的 `AutoSavePeriod` 写盘。键盘扩展经常被系统回收，`terminateExtension()` 又是直接 `_exit`，两次保存之间学到的词会全部丢失。
   - **建议：** 在 `KeyboardViewController.viewWillDisappear` 里调用 `instance->save()`（通过 `dispatcher->schedule`），每分钟最多一次。安卓提交 `4ae78b5e` 做了同样的事。
2. **App Group 模式下，主应用可能覆盖键盘学到的词。** `src/AppDelegate.swift` 每次启动和回到前台，都把 `Documents/data` 整个同步到 App Group 的 `data`。`common/util.swift` 的 `sync()` 对内容不同的文件直接覆盖，没有排除任何文件。只要 `Documents/data/pinyin/user.dict` 或 `user.history` 存在，键盘学到的词每次都会被它覆盖。从安卓备份 zip 导入数据后，这两个文件就可能出现在 Documents 里（需核实导入的落点）。
   - **建议：** 这次同步跳过 `data/pinyin/user.dict` 和 `data/pinyin/user.history`，与 `src/SyncConfigView.swift` 第 82 行 `/list` 的排除规则保持一致。从备份导入的这两个文件，应改走第 4 节的合并流程。
3. **主应用的 Fcitx 实例也可能写这两个文件。** App Group 模式下，主应用和键盘的 `FCITX_DATA_HOME` 都是 App Group 下的 `data`，见 `common/runtime_main.cpp` 第 79 到 85 行。如果主应用的实例加载过拼音插件，比如打开了拼音设置页，它的自动保存会用自己内存里的旧数据覆盖键盘的文件（需核实主应用是否会加载拼音插件）。
   - **建议：** 主应用进程里禁止拼音插件保存用户数据，或者在主进程把 `AutoSavePeriod` 设为 0。
4. **没有 App Group 时，词库只在键盘自己的容器里。** `/list` 已经刻意排除了这两个文件，所以主应用拿不到键盘学到的词，导出需要键盘主动上传，见第 4.2 节。

## 4. iOS 实现方案

原则：用户词库归键盘进程所有，所有修改都在键盘进程里、在拼音引擎不持有文件时进行。主应用只负责解析文件和展示界面。

### 4.1 导入

```
主应用「数据管理」                         中文键盘扩展
选择 user-dictionary.txt
解析（第 2.2 节）→ 规范化 TSV
写待导入文件 import-<UUID>.tsv  ──────▶  出现时发现待导入文件
提示「已解析 N 个词，跳过 M 行」          保存 → 合并 → 重新加载拼音用户数据
                                          记下已应用的 UUID，提示「已并入 N 个词」
```

1. **主应用。** 「数据管理」增加「导入谷歌拼音用户词库」。解析后写出待导入文件，内容就是 `toTsv` 格式，文件名带 UUID，用于防止重复应用。
   - **有 App Group：** 写到 App Group 的 `tmp/googlepinyin/import-<UUID>.tsv`，然后调用现有的 `requestReload()`，让键盘下次出现时处理。
   - **没有 App Group：** 写到 `Documents/data/googlepinyin/import-<UUID>.tsv`，并在 `SyncConfigView.listHandler` 里加上 `enumerate("data/googlepinyin")`。用户点「同步配置」时，键盘会把它下载下来。
2. **键盘：何时处理。** 冷启动时在 `startKeyboardFcitx` 之前处理，这时拼音引擎还没加载，直接合并文件即可。键盘已在运行时，在 `viewWillAppear` 处理 reload 标记的地方，以及同步配置下载完成之后，按下一步的热加载流程处理。
3. **键盘：热加载。** 在 Fcitx 线程上依次执行：
   1. `instance->save()`，先把内存里学到的词写盘。
   2. 对每个未应用的待导入文件调用 `mergeIntoUserData`。路径是 `FCITX_DATA_HOME/pinyin/user.dict` 和 `user.history`。
   3. 让拼音引擎重新读入这两个文件。上游拼音插件没有这个入口，需要加一个补丁，见下方代码。
   4. 把 UUID 记进键盘私有的 `tmp/googlepinyin-applied.json`。有 App Group 时，同时删掉待导入文件。
   5. 用现有的 `iosnotifications` 提示新增和已有的词数。
4. **没有补丁时的退路。** 先只做冷启动处理，键盘运行中发现待导入文件就 `save()` 后调用 `terminateExtension()`，下次出现时冷启动完成合并。体验差一点，但不用改拼音插件。

给 fcitx5-chinese-addons 的补丁，放进 `patches/`。它把构造函数里读取两个文件的代码提成函数，并增加一个子配置入口：

```cpp
// im/pinyin/pinyin.h
void loadUserData();

// im/pinyin/pinyin.cpp：把构造函数中加载 pinyin/user.dict 和 pinyin/user.history
// 的两段 do { ... } while (0) 原样移进 loadUserData()，构造函数改为调用它。
void PinyinEngine::loadUserData() {
    // 原构造函数中的两段加载代码
}

void PinyinEngine::setSubConfig(const std::string &path, const RawConfig &config) {
    ...
    } else if (path == "reloaduserdata") {
        loadUserData();
    }
}
```

键盘侧调用方式：`instance->addonManager().addon("pinyin")->setSubConfig("reloaduserdata", fcitx::RawConfig{})`。调用后对当前输入上下文执行一次 `reset`，避免旧的候选残留。

### 4.2 导出

导出的数据来源是键盘的 `user.dict` 和 `user.history`，由主应用格式化成谷歌文件并交给系统分享。

- **有 App Group：** 主应用直接对 App Group 里的两个文件调用 `readUserWords`，只读不写。第 3 节第 1 条修好后，键盘每次收起都会保存，数据最多落后一分钟。
- **没有 App Group：** 键盘在同步配置的剪贴板阶段之后、上报 `/done` 之前，先 `instance->save()`，再用 `readUserWords` 和 `toTsv` 生成快照，`POST /googlepinyin/userwords` 上传给主应用。主应用把快照存到 `Documents/tmp/googlepinyin/keyboard-words.tsv`，导出时使用，并在界面上显示快照时间，提示用户先同步配置。新端点按 `docs/synchronization.md` 的「Adding another bidirectional dataset」要求，注册到现有的 `ServerManager`。

### 4.3 构建

- 键盘和主应用两个目标都要编译 `userdata.cpp`，链接 `LibIME::Pinyin` 和 `Fcitx5::Utils`。仓库里已有 `cmake/FindLibIMEPinyin.cmake`。
- `userdata.cpp` 没有平台依赖，可以加进 `tests/backup` 的 macOS 主机测试，直接测合并和读取。

## 5. 验收清单

安卓设备端测试覆盖了以下场景，iOS 请做同样的验证，至少覆盖前六条。

- [ ] 合并后读回：新增、已有、非法拼音三个计数正确，扩展 B 区汉字可用。
- [ ] 词频换算：词频 1 写入 1 次，100 写入 5 次；再次导入同一个词，次数在原有基础上增加。
- [ ] 导入两个同音词后，谷歌词频高的排在前面。
- [ ] 反复选词频低的那个，它会升到第一。
- [ ] 导出的词频随日常输入增加。
- [ ] 「忘记候选」能删掉导入的词，导出时不再出现。
- [ ] 同一份导入不会被应用两次，包括没有 App Group 时重复同步配置的情况。
- [ ] 键盘被 `terminateExtension()` 杀掉后，一分钟前学到的词仍在。
- [ ] App Group 模式下，主应用回到前台不会覆盖键盘的 `user.dict`。

## 6. 与安卓的对应关系

| 环节 | 安卓 `mod` 分支 | iOS |
| --- | --- | --- |
| 解析谷歌文件 | `GooglePinyinUserDict.kt` | Swift 移植 |
| 合并与读取 | `googlepinyin/userdata.cpp` | 同一份 C++ |
| 让引擎放开文件 | 保存后停掉整个 Fcitx，改完再启动 | 键盘进程内保存、合并，再用补丁热加载 |
| 导入入口 | 拼音词库页菜单，或直接打开 .txt | 主应用「数据管理」 |
| 导出来源 | 本机 `user.dict` 和 `user.history` | App Group 直接读，没有 App Group 则用键盘上传的快照 |
| 及时保存 | 收起键盘时保存，每分钟最多一次 | 同左，在 `viewWillDisappear` 里做 |

另外，`docs/ios-fcitx5-google-pinyin-guide.md` 第 5.2 节还写着导入后会生成「谷歌拼音_user-dictionary」词库。这是旧行为，iOS 改完后请一并更新。
