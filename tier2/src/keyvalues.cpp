#include "hsg100/keyvalues.hpp"

#include <cctype>

namespace hsg100 {

namespace {

struct Lexer {
    const char *p;
    const char *end;
    int line = 1;
    std::string err;

    bool fail(const std::string &msg) {
        if (err.empty()) err = "line " + std::to_string(line) + ": " + msg;
        return false;
    }

    void skipWs() {
        while (p < end) {
            char c = *p;
            if (c == '\n') { ++line; ++p; }
            else if (c == ' ' || c == '\t' || c == '\r') { ++p; }
            else if (c == '/' && p + 1 < end && p[1] == '/') {
                while (p < end && *p != '\n') ++p;
            } else if (c == '/' && p + 1 < end && p[1] == '*') {
                p += 2;
                while (p < end && !(p[0] == '*' && p + 1 < end && p[1] == '/')) {
                    if (*p == '\n') ++line;
                    ++p;
                }
                if (p < end) p += 2;
            } else break;
        }
    }

    char peek() { return p < end ? *p : '\0'; }

    // Reads a token: quoted string (with \" and \\ escapes) or bare word.
    bool token(std::string *out) {
        skipWs();
        if (p >= end) return fail("unexpected end of file");
        if (*p == '"') {
            ++p;
            out->clear();
            while (p < end && *p != '"') {
                if (*p == '\\' && p + 1 < end && (p[1] == '"' || p[1] == '\\')) ++p;
                if (*p == '\n') ++line;
                out->push_back(*p++);
            }
            if (p >= end) return fail("unterminated string");
            ++p; // closing quote
            return true;
        }
        if (*p == '{' || *p == '}') return fail("expected a key, got brace");
        out->clear();
        while (p < end && !isspace(static_cast<unsigned char>(*p)) && *p != '{' && *p != '}' && *p != '"')
            out->push_back(*p++);
        if (out->empty()) return fail("empty token");
        return true;
    }
};

} // namespace

// Friend of KeyValue (see header) — owns the recursive-descent parse so it
// can fill private fields. Grammar (loops where nesting repeats):
//   nodes := ( key ( value | '{' nodes '}' ) )*
struct KvParser {
    static bool parseNodes(Lexer &lx, std::vector<KeyValue> *out) {
        for (;;) {
            lx.skipWs();
            if (lx.p >= lx.end) return true;
            if (lx.peek() == '}') return true; // caller consumes

            KeyValue kv;
            if (!lx.token(&kv.key_)) return false;
            lx.skipWs();
            if (lx.peek() == '{') {
                ++lx.p;
                if (!parseNodes(lx, &kv.children_)) return false;
                lx.skipWs();
                if (lx.peek() != '}') return lx.fail("expected '}'");
                ++lx.p;
            } else {
                if (!lx.token(&kv.value_)) return false;
                kv.has_value_ = true;
            }
            out->push_back(std::move(kv));
        }
    }

    static std::unique_ptr<KeyValue> run(const std::string &text, std::string *err) {
        Lexer lx{text.c_str(), text.c_str() + text.size(), 1, {}};
        auto root = std::make_unique<KeyValue>();
        if (!parseNodes(lx, &root->children_)) {
            if (err) *err = lx.err;
            return nullptr;
        }
        lx.skipWs();
        if (lx.p != lx.end) {
            if (err) *err = "line " + std::to_string(lx.line) + ": trailing characters (unbalanced '}')";
            return nullptr;
        }
        return root;
    }
};

std::unique_ptr<KeyValue> KeyValue::parse(const std::string &text, std::string *err) {
    return KvParser::run(text, err);
}

const KeyValue *KeyValue::find(const std::string &key) const {
    for (const auto &c : children_)
        if (c.key_ == key) return &c;
    return nullptr;
}

std::vector<const KeyValue *> KeyValue::findAll(const std::string &key) const {
    std::vector<const KeyValue *> out;
    for (const auto &c : children_)
        if (c.key_ == key) out.push_back(&c);
    return out;
}

} // namespace hsg100
