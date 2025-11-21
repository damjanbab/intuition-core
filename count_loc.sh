#!/bin/bash
# Count lines of code in Clojure (.clj, .cljs, .cljc) and EDN (.edn) files

echo "=== Intuition Core - Lines of Code Analysis ==="
echo ""

# Clojure files
clj_files=$(find . -type f \( -name "*.clj" -o -name "*.cljs" -o -name "*.cljc" \) | wc -l)
clj_lines=$(find . -type f \( -name "*.clj" -o -name "*.cljs" -o -name "*.cljc" \) -exec cat {} \; | wc -l)

# EDN files
edn_files=$(find . -type f -name "*.edn" | wc -l)
edn_lines=$(find . -type f -name "*.edn" -exec cat {} \; | wc -l)

# Total
total_files=$((clj_files + edn_files))
total_lines=$((clj_lines + edn_lines))

echo "Clojure files (.clj/.cljs/.cljc):"
echo "  Files: $clj_files"
echo "  Lines: $clj_lines"
echo ""
echo "EDN files (.edn):"
echo "  Files: $edn_files"
echo "  Lines: $edn_lines"
echo ""
echo "==================================="
echo "TOTAL:"
echo "  Files: $total_files"
echo "  Lines: $total_lines"
echo "==================================="
echo ""

# Breakdown by directory
echo "Breakdown by major directory:"
echo ""
echo "Source code (src/):"
find ./src -type f \( -name "*.clj" -o -name "*.cljs" -o -name "*.cljc" \) 2>/dev/null -exec cat {} \; | wc -l

echo "Tests (test/):"
find ./test -type f \( -name "*.clj" -o -name "*.cljs" -o -name "*.cljc" \) 2>/dev/null -exec cat {} \; | wc -l

echo "Dictionary/Resources (resources/):"
find ./resources -type f -name "*.edn" 2>/dev/null -exec cat {} \; | wc -l

echo "Dev helpers (dev/):"
find ./dev -type f \( -name "*.clj" -o -name "*.cljs" -o -name "*.cljc" \) 2>/dev/null -exec cat {} \; | wc -l
