"""Optional Swift grammar check; NOT Swift compilation, type checking or runtime testing.

Dependencies: requirements-dev.txt. The actual compiler gate remains the Xcode workflow.
"""
from pathlib import Path
import sys

from tree_sitter import Language, Parser
import tree_sitter_swift


def main():
    root = Path(__file__).resolve().parents[1]
    parser = Parser(Language(tree_sitter_swift.language()))
    failures = []
    files = sorted(root.rglob("*.swift"))
    for path in files:
        source = path.read_bytes()
        tree = parser.parse(source)
        pending = [tree.root_node]
        while pending:
            node = pending.pop()
            if node.type == "ERROR" or node.is_missing:
                failures.append(f"{path.relative_to(root)}:{node.start_point.row + 1}: {node.type}")
            pending.extend(node.children)
    print("\n".join(failures)) if failures else None
    print(f"Swift grammar only: {len(files)} files; {len(failures)} errors. NOT compilation/type checking.")
    return bool(failures)


if __name__ == "__main__":
    sys.exit(main())
