"""Checks that every Paper API call in the plugin's classes links on a given server's API.

    python3 -I scripts/compat/api_links.py <main-classes> <newerApi-classes> <paper-api.jar>...

Run `./gradlew compileJava compileNewerApiJava` first; the class folders are build/classes/java/main and
build/classes/java/newerApi. Each paper-api jar is one server version (Gradle caches them under
~/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api). Main must link on every supported
server. A newerApi class is checked only against a jar that has what its @ConditionalOnApi names, since
the plugin never loads it elsewhere. Main compiles against the oldest API, so javac already proved it links
there; this catches what changed later: a removed method or field, a changed descriptor, and a class that
became an interface or the reverse (an invokevirtual on an interface fails with
IncompatibleClassChangeError). The exit status is 1 when any jar has a problem.

Members inherited from types outside the jar (Adventure, Guava, the JDK) are taken as present.
"""
import os
import struct
import sys
import zipfile

API_PREFIXES = ("org/bukkit/", "io/papermc/paper/", "com/destroystokyo/paper/", "org/spigotmc/")
GATE = "Lgg/moonrise/hawkeye/util/compat/ConditionalOnApi;"
FIELD, METHOD, INTERFACE_METHOD = 9, 10, 11


class ClassFile:
    """The parts of a class file this check needs: hierarchy, members, references, class annotations."""

    def __init__(self, data):
        self.data = data
        self.pos = 10
        self.cp = self._constant_pool()
        access, this, sup = self._u2(), self._u2(), self._u2()
        self.interface = bool(access & 0x0200)
        self.name = self._class(this)
        self.super = self._class(sup) if sup else None
        self.interfaces = [self._class(self._u2()) for _ in range(self._u2())]
        self.fields = self._members()
        self.methods = self._members()
        self.annotations = self._class_annotations()
        self.refs = []
        self.classes = set()
        for entry in self.cp:
            if entry is None or entry[0] == 1:
                continue
            if entry[0] == 7:
                self.classes.add(self._utf(entry[1]))
            elif entry[0] in (FIELD, METHOD, INTERFACE_METHOD):
                name_and_type = self.cp[entry[2]]
                self.refs.append((entry[0], self._class(entry[1]), self._utf(name_and_type[1]),
                                  self._utf(name_and_type[2])))

    def _u1(self):
        self.pos += 1
        return self.data[self.pos - 1]

    def _u2(self):
        self.pos += 2
        return struct.unpack(">H", self.data[self.pos - 2:self.pos])[0]

    def _u4(self):
        self.pos += 4
        return struct.unpack(">I", self.data[self.pos - 4:self.pos])[0]

    def _utf(self, index):
        return self.cp[index][1]

    def _class(self, index):
        return self._utf(self.cp[index][1])

    def _constant_pool(self):
        count = struct.unpack(">H", self.data[8:10])[0]
        cp = [None] * count
        index = 1
        while index < count:
            tag = self._u1()
            if tag == 1:
                length = self._u2()
                cp[index] = (1, self.data[self.pos:self.pos + length].decode("utf-8", "replace"))
                self.pos += length
            elif tag in (3, 4):
                self.pos += 4
            elif tag in (5, 6):
                self.pos += 8
                index += 1
            elif tag in (7, 8, 16, 19, 20):
                cp[index] = (tag, self._u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                cp[index] = (tag, self._u2(), self._u2())
            elif tag == 15:
                self.pos += 3
            else:
                raise ValueError("unknown constant pool tag %d" % tag)
            index += 1
        return cp

    def _members(self):
        members = set()
        for _ in range(self._u2()):
            self.pos += 2
            name, descriptor = self._utf(self._u2()), self._utf(self._u2())
            for _ in range(self._u2()):
                self.pos += 2
                length = self._u4()
                self.pos += length
            members.add((name, descriptor))
        return members

    def _class_annotations(self):
        annotations = {}
        for _ in range(self._u2()):
            name = self._utf(self._u2())
            end = self._u4() + self.pos
            if name == "RuntimeVisibleAnnotations":
                for _ in range(self._u2()):
                    annotation_type, values = self._annotation()
                    annotations[annotation_type] = values
            self.pos = end
        return annotations

    def _annotation(self):
        annotation_type = self._utf(self._u2())
        values = {}
        for _ in range(self._u2()):
            name = self._utf(self._u2())
            values[name] = self._element()
        return annotation_type, values

    def _element(self):
        tag = chr(self._u1())
        if tag == "s":
            return self._utf(self._u2())
        if tag == "[":
            return [self._element() for _ in range(self._u2())]
        if tag == "e":
            self.pos += 4
            return None
        if tag == "@":
            return self._annotation()
        self.pos += 2
        return None


class Api:
    """One paper-api jar, read lazily."""

    def __init__(self, path):
        self.zip = zipfile.ZipFile(path)
        self.names = {name[:-6] for name in self.zip.namelist() if name.endswith(".class")}
        self.cache = {}

    def get(self, name):
        if name not in self.names:
            return None
        if name not in self.cache:
            self.cache[name] = ClassFile(self.zip.read(name + ".class"))
        return self.cache[name]

    def resolve(self, owner, name, descriptor, field):
        """True when found, False when missing, None when the search left the jar."""
        seen, stack, left = set(), [owner], False
        while stack:
            current = stack.pop()
            if current in seen:
                continue
            seen.add(current)
            info = self.get(current)
            if info is None:
                left = True
                continue
            if (name, descriptor) in (info.fields if field else info.methods):
                return True
            stack.extend([info.super] if info.super else [])
            stack.extend(info.interfaces)
        return None if left else False

    def has(self, gate):
        """Whether the jar has a ConditionalOnApi name: a class, or Class#method by name."""
        type_name, _, method = gate.partition("#")
        internal = type_name.replace(".", "/")
        if not method:
            return internal in self.names
        seen, stack = set(), [internal]
        while stack:
            current = stack.pop()
            info = self.get(current) if current not in seen else None
            seen.add(current)
            if info is None:
                continue
            if any(name == method for name, _ in info.methods):
                return True
            stack.extend([info.super] if info.super else [])
            stack.extend(info.interfaces)
        return False


def classes(folder):
    for root, _, files in os.walk(folder):
        for file in files:
            if file.endswith(".class"):
                with open(os.path.join(root, file), "rb") as handle:
                    yield ClassFile(handle.read())


def problems(info, api):
    found = set()
    for name in info.classes:
        base = name.lstrip("[")
        base = base[1:-1] if base.startswith("L") else base
        if base.startswith(API_PREFIXES) and base not in api.names:
            found.add("missing class %s" % base)
    for kind, owner, name, descriptor in info.refs:
        if owner.startswith("[") or not owner.startswith(API_PREFIXES):
            continue
        target = api.get(owner)
        if target is None:
            found.add("missing class %s" % owner)
            continue
        if kind == METHOD and target.interface:
            found.add("%s is an interface on this server (invokevirtual %s)" % (owner, name))
        if kind == INTERFACE_METHOD and not target.interface:
            found.add("%s is a class on this server (invokeinterface %s)" % (owner, name))
        if api.resolve(owner, name, descriptor, kind == FIELD) is False:
            found.add("missing %s %s.%s%s" % ("field" if kind == FIELD else "method", owner, name, descriptor))
    return found


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    main_classes = list(classes(sys.argv[1]))
    newer_classes = list(classes(sys.argv[2]))
    gates = {info.name: info.annotations.get(GATE, {}).get("value", []) for info in newer_classes}
    failed = False
    for jar in sys.argv[3:]:
        api = Api(jar)
        report = set()
        active = []
        for info in main_classes:
            report |= {"%s: %s" % (info.name, problem) for problem in problems(info, api)}
        for info in newer_classes:
            gate = gates.get(info.name.split("$")[0], [])
            if not gate:
                report.add("%s: no @ConditionalOnApi" % info.name)
            elif all(api.has(name) for name in gate):
                if "$" not in info.name:
                    active.append(info.name.rsplit("/", 1)[-1])
                report |= {"%s: %s" % (info.name, problem) for problem in problems(info, api)}
        print("%s: %d problem(s); newerApi active: %s" % (os.path.basename(jar), len(report),
                                                          ", ".join(sorted(active)) or "none"))
        for line in sorted(report):
            print("  " + line)
        failed = failed or bool(report)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
