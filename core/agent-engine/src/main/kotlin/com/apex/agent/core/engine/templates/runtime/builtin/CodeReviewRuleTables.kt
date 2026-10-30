package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity

/**
 * ═══ 内置模板行为 · 代码审查 —— 逐语言静态规则表（PR 5）═══
 *
 * 代码审查行为的确定性分析内核：**先于大模型**跑一遍逐语言规则 +
 * 语言无关的结构规则，产出带位置/证据/建议的发现清单。LLM 拿到的
 * 是「已带证据的任务书」，评审从核对开始而不是从零扫描开始。
 *
 * 规则纪律：
 * - 每条规则 = 正则形态 + 严重度 + 修复建议（全部确定性，无 LLM 参与）；
 * - 命中定位到「第 N 行」（多行命中取首个，evidence 截 80 字防打爆）；
 * - 同一规则多处命中按行去重上报（上限防刷屏）；
 * - 严重度只升不降：安全/正确性 BLOCKER，性能/可维护性 IMPORTANT，
 *   风格 MINOR —— 与模板正文的三级标记（🔴🟡🟢）严格对齐。
 */

/** 单条静态规则定义。 */
data class CodeReviewRule(
    val ruleId: String,
    val category: TemplateFindingCategory,
    val severity: TemplateSeverity,
    val pattern: Regex,
    val title: String,
    val suggestion: String,
    /** 单文件内该规则最多上报次数（防巨量重复刷屏）。 */
    val maxReports: Int = 3,
    /** true = 该模式出现在注释行也算命中（如 TODO 标记本身就在注释里）。 */
    val matchesInComment: Boolean = false
)

/** 语言 → 规则表。 */
object CodeReviewRuleTables {

    // ═══ Kotlin ═══

    val KOTLIN: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "kotlin.double-bang",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""!!(?!\s*[;=])|!!\."""),
            title = "非空断言 `!!` 脆弱",
            suggestion = "改用安全调用 `?.` + 空缺省（`?:`），或前置显式判空后智能转换"
        ),
        CodeReviewRule(
            ruleId = "kotlin.print-stacktrace",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bprintStackTrace\s*\("""),
            title = "printStackTrace 遗留",
            suggestion = "换用统一日志通道（logger.error + 上下文字段），生产分支不留栈直印"
        ),
        CodeReviewRule(
            ruleId = "kotlin.println-debug",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bprintln\s*\("""),
            title = "println 调试残留",
            suggestion = "调试输出改走日志分级，或删除"
        ),
        CodeReviewRule(
            ruleId = "kotlin.globalscope",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\bGlobalScope\b"""),
            title = "GlobalScope 生命周期失控",
            suggestion = "改用受作用域管理的 CoroutineScope（如 viewModelScope / 结构化并发作用域）"
        ),
        CodeReviewRule(
            ruleId = "kotlin.runblocking-misuse",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\brunBlocking\s*\{"""),
            title = "runBlocking 阻塞调用点",
            suggestion = "确认是否真需阻塞桥接；常规路径应改为 suspend 传导或 async"
        ),
        CodeReviewRule(
            ruleId = "kotlin.unchecked-cast",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bas\s+[A-Z]\w*\b(?!.*\?)"""),
            title = "未检查强转 `as`",
            suggestion = "改用安全转换 `as?` + 空处理，或泛型收窄类型"
        ),
        CodeReviewRule(
            ruleId = "kotlin.lateinit",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\blateinit\s+var\b"""),
            title = "lateinit 延迟初始化",
            suggestion = "优先可空类型 + lateinit 兜底语义明确化；访问前确保初始化路径唯一"
        ),
        CodeReviewRule(
            ruleId = "kotlin.platform-type",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""=\s*findViewById<"""),
            title = "平台类型直取（Java 互操作）",
            suggestion = "显式标注可空性（`as?` 或 `?`），防止 Java 侧空值穿透"
        ),
        CodeReviewRule(
            ruleId = "kotlin.thread-sleep",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bThread\.sleep\s*\("""),
            title = "Thread.sleep 硬等待",
            suggestion = "协程场景改 `delay`；轮询等待改为事件/回调驱动"
        ),
        CodeReviewRule(
            ruleId = "kotlin.force-unwrap-arg",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\(\s*!!\s*\)|!!\s*\)"""),
            title = "括号内非空断言",
            suggestion = "把断言上移到变量绑定处并给出空缺省，保持表达式纯净"
        )
    )

    // ═══ Java ═══

    val JAVA: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "java.print-stacktrace",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bprintStackTrace\s*\("""),
            title = "printStackTrace 遗留",
            suggestion = "换用日志框架（SLF4J + error 级别），附异常上下文"
        ),
        CodeReviewRule(
            ruleId = "java.system-out",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bSystem\.out\.print"""),
            title = "System.out 调试输出",
            suggestion = "删除或替换为日志调用"
        ),
        CodeReviewRule(
            ruleId = "java.empty-catch",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""catch\s*\([^)]*\)\s*\{\s*\}"""),
            title = "空 catch 吞异常",
            suggestion = "至少记录日志并决定传播/恢复策略；禁止静默吞异常"
        ),
        CodeReviewRule(
            ruleId = "java.raw-thread",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bnew\s+Thread\s*\("""),
            title = "裸线程创建",
            suggestion = "改用线程池（ExecutorService）复用线程"
        ),
        CodeReviewRule(
            ruleId = "java.simpledateformat-static",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""(static|private\s+static)\s+.*SimpleDateFormat"""),
            title = "共享 SimpleDateFormat 非线程安全",
            suggestion = "改用 ThreadLocal 包装、局部变量，或 java.time 格式化器（DateTimeFormatter 线程安全）"
        ),
        CodeReviewRule(
            ruleId = "java.thread-sleep",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bThread\.sleep\s*\("""),
            title = "Thread.sleep 硬等待",
            suggestion = "改为条件等待（CountDownLatch/wait-notify）或调度框架"
        ),
        CodeReviewRule(
            ruleId = "java.raw-type",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\b(List|Map|Set)\s+\w+\s*="""),
            title = "原始类型（无泛参）",
            suggestion = "补全泛型参数，消除隐式强转"
        ),
        CodeReviewRule(
            ruleId = "java.string-concat-loop",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bfor\s*\([^)]*\)\s*\{[^}]*\+=\s*"""),
            title = "循环内字符串拼接",
            suggestion = "改用 StringBuilder 或 String.join"
        ),
        CodeReviewRule(
            ruleId = "java.print-formatted",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bSystem\.err\.print"""),
            title = "System.err 直写",
            suggestion = "统一日志通道，stderr 留给真正的致命错误"
        ),
        CodeReviewRule(
            ruleId = "java.random-instance",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bnew\s+Random\s*\("""),
            title = "java.util.Random 非安全随机",
            suggestion = "安全场景（令牌/盐值/密钥）必须用 SecureRandom"
        )
    )

    // ═══ Python ═══

    val PYTHON: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "python.bare-except",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""^\s*except\s*:(""|$)""", RegexOption.MULTILINE),
            title = "裸 except 捕获一切",
            suggestion = "捕获具体异常类型（如 ValueError），避免吞掉 KeyboardInterrupt/SystemExit"
        ),
        CodeReviewRule(
            ruleId = "python.except-pass",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""except\s+\w+(\s+as\s+\w+)?\s*:\s*\n\s*pass"""),
            title = "except 后直接 pass 吞异常",
            suggestion = "记录日志或重新抛出；静默吞异常会掩盖真实故障"
        ),
        CodeReviewRule(
            ruleId = "python.mutable-default",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""def\s+\w+\s*\([^)]*=\s*(\[\]|\{\})"""),
            title = "可变默认参数",
            suggestion = "默认值用 None，函数体内再初始化（可变默认值在调用间共享）"
        ),
        CodeReviewRule(
            ruleId = "python.eq-none",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""==\s*None|!=\s*None"""),
            title = "用 == 判 None",
            suggestion = "改用 `is None` / `is not None`（身份比较而非相等比较）"
        ),
        CodeReviewRule(
            ruleId = "python.eval",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\beval\s*\(|\bexec\s*\("""),
            title = "eval/exec 动态执行",
            suggestion = "改用 ast.literal_eval（只解析字面量）或显式解析器；eval 是注入主入口"
        ),
        CodeReviewRule(
            ruleId = "python.print-debug",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bprint\s*\("""),
            title = "print 调试残留",
            suggestion = "改用 logging（分级 + 格式化），便于生产开关"
        ),
        CodeReviewRule(
            ruleId = "python.open-no-with",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""=\s*open\s*\((?![^)]*\)\s*as)"""),
            title = "open 未用 with 管理",
            suggestion = "改用 `with open(...) as f:`，异常路径也保证关闭"
        ),
        CodeReviewRule(
            ruleId = "python.type-compare",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""type\s*\(\s*\w+\s*\)\s*=="""),
            title = "type() 相等比较",
            suggestion = "改用 isinstance（兼容子类）"
        ),
        CodeReviewRule(
            ruleId = "python.global-keyword",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""^\s*global\s+\w+""", RegexOption.MULTILINE),
            title = "global 全局变量写",
            suggestion = "改为显式传参/返回值，或用类封装状态"
        ),
        CodeReviewRule(
            ruleId = "python.string-format-in-sql",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""(execute|executemany)\s*\(\s*[fF]""""),
            title = "f-string 拼 SQL（注入）",
            suggestion = "改用参数化查询（占位符传参），杜绝字符串拼接 SQL"
        )
    )

    // ═══ JavaScript ═══

    val JAVASCRIPT: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "js.var-declare",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bvar\s+\w+\s*="""),
            title = "var 声明（函数作用域陷阱）",
            suggestion = "改用 const（默认）或 let（需要重绑定时）"
        ),
        CodeReviewRule(
            ruleId = "js.loose-equality",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""[^=!<>]==[^=]|!=[^=]"""),
            title = "宽松相等 ==/!=",
            suggestion = "改用 ===/!==（杜绝隐式类型转换）"
        ),
        CodeReviewRule(
            ruleId = "js.eval",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\beval\s*\("""),
            title = "eval 动态执行",
            suggestion = "改用 JSON.parse 或显式解析；eval 开启注入面"
        ),
        CodeReviewRule(
            ruleId = "js.innerhtml",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\.innerHTML\s*="""),
            title = "innerHTML 直写（XSS）",
            suggestion = "改用 textContent，或对富文本做净化（DOMPurify）"
        ),
        CodeReviewRule(
            ruleId = "js.document-write",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bdocument\.write\s*\("""),
            title = "document.write 阻塞渲染",
            suggestion = "改用 DOM API（createElement/appendChild）"
        ),
        CodeReviewRule(
            ruleId = "js.console-debug",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bconsole\.(log|debug)\s*\("""),
            title = "console 调试残留",
            suggestion = "删除或换为可配置的日志工具"
        ),
        CodeReviewRule(
            ruleId = "js.settimeout-string",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""setTimeout\s*\(\s*['"`]"""),
            title = "setTimeout 传字符串（隐式 eval）",
            suggestion = "改传函数引用或箭头函数"
        ),
        CodeReviewRule(
            ruleId = "js-array-mutation",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\.forEach\s*\(\s*\w+\s*=>\s*\{[^}]*\.push\s*\("""),
            title = "forEach 内 push 收集",
            suggestion = "改用 map 直接映射，语义更清晰"
        )
    )

    // ═══ TypeScript ═══

    val TYPESCRIPT: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "ts.any-type",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex(""":\s*any\b|<any>"""),
            title = "any 类型逃逸",
            suggestion = "改用具体类型、unknown + 收窄，或泛型参数"
        ),
        CodeReviewRule(
            ruleId = "ts.as-any",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bas\s+any\b"""),
            title = "as any 强转",
            suggestion = "补全类型定义或用类型守卫收窄"
        ),
        CodeReviewRule(
            ruleId = "ts-ts-ignore",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""//\s*@ts-ignore|//\s*@ts-nocheck"""),
            title = "@ts-ignore 压制检查",
            suggestion = "修复类型问题本身；确需豁免用 @ts-expect-error 并附原因"
        ),
        CodeReviewRule(
            ruleId = "ts.non-null-assert",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\w!\s*[.;,)\]]"""),
            title = "非空断言 `!`",
            suggestion = "改用可选链 `?.` 或显式空检查"
        ),
        CodeReviewRule(
            ruleId = "ts.enum-implicit",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\benum\s+\w+\s*\{[^}]*\}"""),
            title = "enum（运行时对象）",
            suggestion = "纯常量集合改 `as const` 字面量联合，零运行时开销"
        ),
        CodeReviewRule(
            ruleId = "ts-double-assert",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bas\s+unknown\s+as\s+"""),
            title = "双重断言绕过类型",
            suggestion = "重构类型边界（判别联合/泛型），双重断言是类型漏洞后门"
        )
    )

    // ═══ Go ═══

    val GO: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "go.ignored-error",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""^\s*(_\s*,\s*_|_)\s*:?=(?!=)\s*(?!range\b)\S""", RegexOption.MULTILINE),
            title = "错误/返回值被 `_` 丢弃",
            suggestion = "至少 `if err != nil` 分支处理；`_ = err` 同样不可接受"
        ),
        CodeReviewRule(
            ruleId = "go.panic-library",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bpanic\s*\("""),
            title = "panic 直接抛出",
            suggestion = "库代码返回 error；panic 只留给不可恢复的初始化错误"
        ),
        CodeReviewRule(
            ruleId = "go.sleep-poll",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\btime\.Sleep\s*\("""),
            title = "Sleep 轮询等待",
            suggestion = "改用 channel/select 通知或 ticker，消除忙等"
        ),
        CodeReviewRule(
            ruleId = "go.empty-interface",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\binterface\s*\{\s*\}"""),
            title = "空接口 interface{}",
            suggestion = "改用 any（Go 1.18+）或定义最小接口约束"
        ),
        CodeReviewRule(
            ruleId = "go.fmt-print",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bfmt\.Print(ln|f)?\s*\("""),
            title = "fmt.Print 调试残留",
            suggestion = "换用 log/slog 结构化日志"
        ),
        CodeReviewRule(
            ruleId = "go.append-in-loop-arg",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""append\s*\(\s*\w+,\s*\w+\.\.\.\s*\)"""),
            title = "append 展开嵌套切片",
            suggestion = "确认容量语义；大切片合并预估容量避免反复扩容"
        )
    )

    // ═══ Rust ═══

    val RUST: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "rust.unwrap",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\.unwrap\s*\(\s*\)"""),
            title = "unwrap 直接解包",
            suggestion = "改用 `?` 传播、expect(带原因) 或 match 显式分支；unwrap 是 panic 入口"
        ),
        CodeReviewRule(
            ruleId = "rust.expect-no-ctx",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\.expect\s*\(\s*""\s*\)"""),
            title = "expect 空原因",
            suggestion = "expect 附上出错原因描述，panic 信息可诊断"
        ),
        CodeReviewRule(
            ruleId = "rust.unsafe",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\bunsafe\s*(\{|\bfn\b)"""),
            title = "unsafe 代码块",
            suggestion = "给每处 unsafe 附安全证明注释；优先安全抽象替代"
        ),
        CodeReviewRule(
            ruleId = "rust.clone-in-loop",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bloop\b|\bwhile\b"""),
            title = "循环体待查（人工核对 clone/分配）",
            suggestion = "循环内 .clone()/Vec 分配应改为借用或预分配"
        ),
        CodeReviewRule(
            ruleId = "rust.as-cast",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bas\s+(u8|u16|u32|u64|i8|i16|i32|i64|usize|isize)\b"""),
            title = "as 数值截断转换",
            suggestion = "改用 try_from（显式处理溢出）"
        ),
        CodeReviewRule(
            ruleId = "rust.println-debug",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bprintln!\s*\("""),
            title = "println! 调试残留",
            suggestion = "改用 log/tracing 宏"
        )
    )

    // ═══ C / C++ ═══

    val C_CPP: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "c.gets",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\bgets\s*\("""),
            title = "gets 无界读取（必炸缓冲区）",
            suggestion = "改用 fgets（带长度上限）"
        ),
        CodeReviewRule(
            ruleId = "c.strcpy",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\b(strcpy|strcat)\s*\("""),
            title = "strcpy/strcat 无界拷贝",
            suggestion = "改用 strncpy/strlcat（显式长度）或 std::string"
        ),
        CodeReviewRule(
            ruleId = "c.sprintf",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\bsprintf\s*\("""),
            title = "sprintf 无界格式化",
            suggestion = "改用 snprintf（带缓冲上限）"
        ),
        CodeReviewRule(
            ruleId = "c.system",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\bsystem\s*\("""),
            title = "system 命令注入面",
            suggestion = "改用 exec* 族 + 参数数组，杜绝 shell 解释"
        ),
        CodeReviewRule(
            ruleId = "c.malloc-nofree",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bmalloc\s*\("""),
            title = "malloc 待核对释放路径",
            suggestion = "确认每个 malloc 有对应 free（或改用 RAII/unique_ptr）"
        ),
        CodeReviewRule(
            ruleId = "c.printf-variable-format",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\bprintf\s*\(\s*[A-Za-z_]\w*\s*[,)]"""),
            title = "printf 格式串是变量（格式化注入）",
            suggestion = "格式串必须是字面量；用户输入只作参数"
        ),
        CodeReviewRule(
            ruleId = "cpp.new-delete",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bnew\s+\w+(\[|;)"""),
            title = "裸 new 手工管理",
            suggestion = "改用 make_unique/make_shared（RAII）"
        ),
        CodeReviewRule(
            ruleId = "c.goto",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bgoto\s+\w+;"""),
            title = "goto 跳转",
            suggestion = "重构控制流；goto 仅错误清理路径可接受"
        )
    )

    // ═══ C# ═══

    val CSHARP: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "cs.async-void",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\basync\s+void\s+\w+\s*\("""),
            title = "async void（异常不可捕获）",
            suggestion = "改用 async Task；async void 仅限事件处理器"
        ),
        CodeReviewRule(
            ruleId = "cs.wait-result",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\.Result\b|\.Wait\s*\("""),
            title = ".Result/.Wait 同步阻塞异步",
            suggestion = "一路 async/await；必须阻塞时用 GetAwaiter().GetResult() 并注明原因"
        ),
        CodeReviewRule(
            ruleId = "cs.string-concat-loop",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\bforeach\s*\([^)]*\)[^;]*\+=\s*"""),
            title = "循环内字符串拼接",
            suggestion = "改用 StringBuilder"
        ),
        CodeReviewRule(
            ruleId = "cs.catch-empty",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""catch\s*(\(\s*\w+\s*(\w+)?\s*\))?\s*\{\s*\}"""),
            title = "空 catch 吞异常",
            suggestion = "记录并决定传播策略"
        )
    )

    // ═══ Swift ═══

    val SWIFT: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "swift.force-unwrap",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\w+!\s*[.;,)\]]"""),
            title = "强制解包 `!`",
            suggestion = "改用 `if let` / `guard let` 或 `??` 缺省"
        ),
        CodeReviewRule(
            ruleId = "swift.try-force",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""\btry!"""),
            title = "try! 强制抛弃错误",
            suggestion = "改用 try + catch 或 try? + 错误处理"
        ),
        CodeReviewRule(
            ruleId = "swift.print-debug",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bprint\s*\("""),
            title = "print 调试残留",
            suggestion = "改用 os.Logger（分级 + 隐私标注）"
        ),
        CodeReviewRule(
            ruleId = "swift.main-actor-mismatch",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""DispatchQueue\.main\.async\s*\{"""),
            title = "GCD 手动回主线程",
            suggestion = "Swift 并发模型下改 @MainActor 标注 + Task { @MainActor in }"
        )
    )

    // ═══ PHP ═══

    val PHP: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "php.eval",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\beval\s*\("""),
            title = "eval 动态执行",
            suggestion = "移除 eval；动态逻辑改显式分支或映射表"
        ),
        CodeReviewRule(
            ruleId = "php.mysql-query",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""\bmysql_query\s*\(|\bmysqli_query\s*\(\s*\$\w+\s*,\s*['"]\s*(SELECT|INSERT|UPDATE|DELETE)""", RegexOption.IGNORE_CASE),
            title = "SQL 字符串直查（注入）",
            suggestion = "改用 PDO 预处理语句（prepare + bindParam）"
        ),
        CodeReviewRule(
            ruleId = "php.echo-get",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            // 普通转义串：raw 串里 `\$_(` 会被当 `\$_` 模板引用（下划线是合法标识符）
            pattern = Regex("echo\\s+\\\$_(GET|POST|REQUEST)"),
            title = "直接回显用户输入（XSS）",
            suggestion = "先 htmlspecialchars 转义再输出"
        ),
        CodeReviewRule(
            ruleId = "php.md5-password",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""md5\s*\(|sha1\s*\("""),
            title = "MD5/SHA1 弱哈希",
            suggestion = "密码用 password_hash（bcrypt/argon2）；完整性校验用 SHA-256+"
        ),
        CodeReviewRule(
            ruleId = "php.var-dump",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""\bvar_dump\s*\(|\bprint_r\s*\("""),
            title = "var_dump/print_r 调试残留",
            suggestion = "删除或换 Monolog 日志"
        )
    )

    // ═══ Shell ═══

    val SHELL: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "sh.rm-rf-var",
            category = TemplateFindingCategory.SECURITY,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""rm\s+(-[a-zA-Z]*f[a-zA-Z]*r|-[a-zA-Z]*r[a-zA-Z]*f)\s+\$"""),
            title = "rm -rf 展开变量",
            suggestion = "变量先判空再拼接，并限定在白名单目录内执行"
        ),
        CodeReviewRule(
            ruleId = "sh.cd-no-check",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""^\s*cd\s+[\w./\"']+$""", RegexOption.MULTILINE),
            title = "cd 无失败检查",
            suggestion = "`cd ... || exit 1`，防止后续命令在错误目录执行"
        ),
        CodeReviewRule(
            ruleId = "sh.unquoted-var",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""=\$\w+[^"'\s]"""),
            title = "赋值右侧变量未引用",
            suggestion = "统一双引号包裹变量（如 \"\$var\"），防空格分词"
        ),
        CodeReviewRule(
            ruleId = "sh.backtick",
            category = TemplateFindingCategory.MAINTAINABILITY,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""`\w+[^`]*`"""),
            title = "反引号命令替换",
            suggestion = "改用 `$(...)`（可嵌套、易读）"
        )
    )

    // ═══ SQL ═══

    val SQL: List<CodeReviewRule> = listOf(
        CodeReviewRule(
            ruleId = "sql.select-star",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.MINOR,
            pattern = Regex("""SELECT\s+\*""", RegexOption.IGNORE_CASE),
            title = "SELECT * 全列拉取",
            suggestion = "显式列清单（减少 IO + 契约明确）"
        ),
        CodeReviewRule(
            ruleId = "sql.leading-like",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""LIKE\s+'%""", RegexOption.IGNORE_CASE),
            title = "前置通配 LIKE（索引失效）",
            suggestion = "避免 '%xxx' 前缀匹配；全文检索场景用 FULLTEXT/倒排"
        ),
        CodeReviewRule(
            ruleId = "sql.no-where-delete",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.BLOCKER,
            pattern = Regex("""(DELETE|UPDATE)\s+(FROM\s+)?\w+(\s+SET[^W]*)?$""", RegexOption.MULTILINE),
            title = "DELETE/UPDATE 疑似无 WHERE",
            suggestion = "全表更新/删除必须显式 WHERE true 并二次确认",
            maxReports = 1
        ),
        CodeReviewRule(
            ruleId = "sql.func-on-index",
            category = TemplateFindingCategory.PERFORMANCE,
            severity = TemplateSeverity.IMPORTANT,
            pattern = Regex("""WHERE\s+\w+\s*\(\s*\w+\s*\)\s*=""", RegexOption.IGNORE_CASE),
            title = "索引列套函数",
            suggestion = "函数包住索引列会让优化器放弃索引；改写为范围条件"
        )
    )

    /** 语言 id → 规则表。 */
    fun rulesFor(language: String?): List<CodeReviewRule> = when (language) {
        "kotlin" -> KOTLIN
        "java" -> JAVA
        "python" -> PYTHON
        "javascript" -> JAVASCRIPT
        "typescript" -> TYPESCRIPT
        "go" -> GO
        "rust" -> RUST
        "c", "cpp" -> C_CPP
        "csharp" -> CSHARP
        "swift" -> SWIFT
        "php" -> PHP
        "shell" -> SHELL
        "sql" -> SQL
        else -> emptyList()
    }
}
