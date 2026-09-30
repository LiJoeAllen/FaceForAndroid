// seeta_compat.h —— 强制 #include 进每个 SeetaFace6 编译单元的兼容头。
//
// 上游代码面向 NDK r16b，其 libc++ 会传递引入大量标准头；新版 NDK（r23+）
// 不再如此，会出现 "no template named 'function' in namespace std" 一类的
// 编译错误。为免改动 vendored 子模块，这里统一补齐源码默认可用的标准头
// （等价于上游在 NDK r16b 下的实际编译环境）。
#pragma once

#include <algorithm>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <functional>
#include <iostream>
#include <limits>
#include <map>
#include <memory>
#include <numeric>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>
