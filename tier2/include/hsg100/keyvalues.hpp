// half-sourceG100 — Tier 2 mod compatibility library
// KeyValues1 (gameinfo.txt / *.vdf) parser.
//
// Supports the Valve KeyValues1 grammar subset needed for gameinfo.txt:
//   - "quoted keys" "quoted values", unquoted tokens
//   - { } nesting, // line comments, /* */ block comments
//   - escaped quotes inside quoted strings
// Deliberately no conditionals ($OS etc.) — gameinfo.txt doesn't use them.

#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

namespace hsg100 {

class KeyValue {
public:
    // Parse KeyValues text. Returns nullptr on syntax error (err gets a message).
    static std::unique_ptr<KeyValue> parse(const std::string &text, std::string *err = nullptr);

    const std::string &key() const { return key_; }
    // First child with key, or nullptr.
    const KeyValue *find(const std::string &key) const;
    // All children with key.
    std::vector<const KeyValue *> findAll(const std::string &key) const;
    // Value if this node is a leaf ("key" "value").
    const std::string &value() const { return value_; }
    bool hasValue() const { return has_value_; }
    const std::vector<KeyValue> &children() const { return children_; }

private:
    std::string key_;
    std::string value_;
    bool has_value_ = false;
    std::vector<KeyValue> children_;

    friend struct KvParser;
};

} // namespace hsg100
