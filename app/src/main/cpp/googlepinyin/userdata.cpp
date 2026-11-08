/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include "userdata.h"

#include <algorithm>
#include <charconv>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <utility>

#include <fcitx-utils/macros.h>
#include <fcitx-utils/stringutils.h>
#include <libime/core/historybigram.h>
#include <libime/pinyin/pinyindictionary.h>
#include <libime/pinyin/pinyinencoder.h>

namespace googlepinyin {

namespace {

constexpr size_t kUserDict = libime::PinyinDictionary::UserDict;

bool fileExists(const std::string &path) {
    std::error_code ec;
    return !path.empty() && std::filesystem::is_regular_file(path, ec);
}

/** encoded pinyin used as history "validation code", same as PinyinContext::learn */
std::string encodePinyin(std::string_view pinyin) {
    auto encoded = libime::PinyinEncoder::encodeFullPinyinWithFlags(
        pinyin, libime::PinyinFuzzyFlag::VE_UE);
    return {encoded.data(), encoded.size()};
}

void loadDict(libime::PinyinDictionary &dict, const std::string &path) {
    if (!fileExists(path)) {
        return;
    }
    dict.load(kUserDict, path.c_str(), libime::PinyinDictFormat::Binary);
}

void loadHistory(libime::HistoryBigram &history, const std::string &path) {
    if (!fileExists(path)) {
        return;
    }
    std::ifstream in(path, std::ios::in | std::ios::binary);
    if (!in) {
        throw std::runtime_error("failed to open " + path);
    }
    history.load(in);
}

/** write to a sibling temp file and rename over [path] */
template <typename Writer>
void replaceFile(const std::string &path, Writer &&writer) {
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::out | std::ios::binary | std::ios::trunc);
        if (!out) {
            throw std::runtime_error("failed to open " + tmp);
        }
        writer(out);
        out.flush();
        if (!out) {
            std::remove(tmp.c_str());
            throw std::runtime_error("failed to write " + tmp);
        }
    }
    std::error_code ec;
    std::filesystem::rename(tmp, path, ec);
    if (ec) {
        std::remove(tmp.c_str());
        throw std::runtime_error("failed to replace " + path + ": " + ec.message());
    }
}

} // namespace

int32_t historyRepeatsForCount(int32_t count) {
    int32_t repeats = 1;
    while (count > 1 && repeats < kMaxHistoryRepeats) {
        count >>= 1;
        repeats++;
    }
    return repeats;
}

MergeResult mergeIntoUserData(const std::string &userDictPath,
                              const std::string &historyPath,
                              std::vector<UserWord> words) {
    libime::PinyinDictionary dict;
    loadDict(dict, userDictPath);
    libime::HistoryBigram history;
    loadHistory(history, historyPath);

    std::stable_sort(words.begin(), words.end(),
                     [](const UserWord &a, const UserWord &b) {
                         return a.count < b.count;
                     });

    MergeResult result;
    for (const auto &w : words) {
        std::string code;
        try {
            code = encodePinyin(w.pinyin);
        } catch (const std::exception &) {
            result.invalid++;
            continue;
        }
        if (w.word.empty() || w.word.find('\0') != std::string::npos) {
            result.invalid++;
            continue;
        }
        if (dict.lookupWord(kUserDict, w.pinyin, w.word)) {
            result.existing++;
        } else {
            // same cost as words learned by PinyinContext
            dict.addWord(kUserDict, w.pinyin, w.word, 0);
            result.added++;
        }
        const std::vector<libime::HistoryBigram::WordWithCode> sentence{
            {w.word, code}};
        for (int32_t i = historyRepeatsForCount(w.count); i > 0; i--) {
            history.addWithCode(sentence);
        }
    }

    replaceFile(userDictPath, [&dict](std::ostream &out) {
        dict.save(kUserDict, out, libime::PinyinDictFormat::Binary);
    });
    replaceFile(historyPath,
                [&history](std::ostream &out) { history.save(out); });
    return result;
}

std::vector<UserWord> readUserWords(const std::string &dictPath,
                                    const std::string &historyPath) {
    std::vector<UserWord> words;
    if (!fileExists(dictPath)) {
        return words;
    }
    libime::PinyinDictionary dict;
    loadDict(dict, dictPath);
    libime::HistoryBigram history;
    const bool hasHistory = fileExists(historyPath);
    if (hasHistory) {
        loadHistory(history, historyPath);
    }

    std::stringstream text;
    dict.save(kUserDict, text, libime::PinyinDictFormat::Text);
    std::string line;
    while (std::getline(text, line)) {
        // same tokenizer as PinyinDictionary::loadText: "word pin'yin cost"
        std::string_view rest = line;
        std::vector<std::string> tokens;
        while (!rest.empty()) {
            std::string token;
            auto consumed = fcitx::stringutils::consumeMaybeEscapedValue(
                rest, FCITX_WHITESPACE, &token);
            if (!consumed.empty()) {
                tokens.push_back(std::move(token));
            }
        }
        if (tokens.size() < 2 || tokens[0].empty()) {
            continue;
        }
        UserWord w{std::move(tokens[0]), std::move(tokens[1]), 1};
        if (hasHistory) {
            try {
                const auto code = encodePinyin(w.pinyin);
                w.count = std::max<int32_t>(
                    1, history.rawUnigramFrequency({w.word, code}));
            } catch (const std::exception &) {
                // keep count 1
            }
        }
        words.push_back(std::move(w));
    }
    return words;
}

std::string toTsv(const std::vector<UserWord> &words) {
    std::string out;
    for (const auto &w : words) {
        // tab/newline cannot be represented in this format, nor in Google Pinyin's
        if (w.word.find_first_of("\t\n\r") != std::string::npos ||
            w.pinyin.find_first_of("\t\n\r") != std::string::npos) {
            continue;
        }
        out.append(w.word).push_back('\t');
        out.append(w.pinyin).push_back('\t');
        out.append(std::to_string(w.count)).push_back('\n');
    }
    return out;
}

std::vector<UserWord> fromTsv(std::string_view tsv) {
    std::vector<UserWord> words;
    while (!tsv.empty()) {
        auto eol = tsv.find('\n');
        auto line = tsv.substr(0, eol);
        tsv = eol == std::string_view::npos ? std::string_view{}
                                            : tsv.substr(eol + 1);
        auto t1 = line.find('\t');
        if (t1 == std::string_view::npos) {
            continue;
        }
        auto t2 = line.find('\t', t1 + 1);
        if (t2 == std::string_view::npos) {
            continue;
        }
        auto countStr = line.substr(t2 + 1);
        int32_t count = 1;
        auto [ptr, ec] = std::from_chars(
            countStr.data(), countStr.data() + countStr.size(), count);
        if (ec != std::errc()) {
            count = 1;
        }
        words.push_back(UserWord{std::string(line.substr(0, t1)),
                                 std::string(line.substr(t1 + 1, t2 - t1 - 1)),
                                 std::max<int32_t>(1, count)});
    }
    return words;
}

} // namespace googlepinyin
