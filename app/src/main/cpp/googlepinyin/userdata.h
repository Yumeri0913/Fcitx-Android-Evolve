/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#ifndef FCITX5_ANDROID_GOOGLEPINYIN_USERDATA_H
#define FCITX5_ANDROID_GOOGLEPINYIN_USERDATA_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

/**
 * Exchange user words between Google Pinyin user dictionary and fcitx5 pinyin user data.
 *
 * Platform independent: only depends on libime and fcitx5-utils, so it can be shared by
 * fcitx5-android (through JNI) and fcitx5-ios.
 *
 * fcitx5 pinyin keeps two user files under `$FCITX_DATA_HOME/pinyin/`:
 * - `user.dict`: words learned while typing (libime PinyinDictionary, binary format)
 * - `user.history`: usage history (libime HistoryBigram), which drives candidate ranking
 *
 * Neither file may be touched while the pinyin engine is running: the engine only reads
 * them on startup and overwrites them on save. Callers must stop the engine first.
 */
namespace googlepinyin {

struct UserWord {
    std::string word;
    /** libime full pinyin, syllables joined by '\'' e.g. "ni'hao" */
    std::string pinyin;
    /** usage count, at least 1 */
    int32_t count = 1;
};

struct MergeResult {
    /** words added to user.dict */
    size_t added = 0;
    /** words already in user.dict, only their history got updated */
    size_t existing = 0;
    /** words rejected because of invalid pinyin */
    size_t invalid = 0;
};

/**
 * How many times a word with Google Pinyin usage [count] is added to history:
 * 1 + floor(log2(count)), capped at [kMaxHistoryRepeats].
 * Logarithmic so that the relative order of Google Pinyin's frequencies is kept without
 * flooding the history pools (128 / 8192 / 65536 sentences).
 */
constexpr int32_t kMaxHistoryRepeats = 5;
int32_t historyRepeatsForCount(int32_t count);

/**
 * Add [words] to user.dict and their usage to user.history.
 * Words are applied in ascending count order, so that the most frequently used ones end up
 * as the most recent history. Missing files are created. Files are replaced atomically.
 */
MergeResult mergeIntoUserData(const std::string &userDictPath,
                              const std::string &historyPath,
                              std::vector<UserWord> words);

/**
 * Read all words of a binary libime pinyin dictionary (e.g. user.dict).
 * When [historyPath] names an existing history file, each word's count is its raw unigram
 * frequency in history (at least 1); otherwise all counts are 1.
 */
std::vector<UserWord> readUserWords(const std::string &dictPath,
                                    const std::string &historyPath);

/** Serialize as UTF-8 lines of "word\tpinyin\tcount\n" */
std::string toTsv(const std::vector<UserWord> &words);

/** Parse UTF-8 lines of "word\tpinyin\tcount"; malformed lines are skipped */
std::vector<UserWord> fromTsv(std::string_view tsv);

} // namespace googlepinyin

#endif // FCITX5_ANDROID_GOOGLEPINYIN_USERDATA_H
