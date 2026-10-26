package com.fitness.ai.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 健身教练 Agent（第 5 步：已挂载三大工具集）。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>{@code wiringMode = EXPLICIT}：模型、记忆工厂、工具集全部按 Bean 名精确指定。</li>
 *   <li>{@code @MemoryId}：以 userId 作为记忆分区键，经 chatMemoryProvider → RedisChatMemoryStore 落 Redis。</li>
 *   <li>{@code @SystemMessage} 中的 {{userProfile}} / {{injuryTags}} 由调用方通过 {@code @V} 传入，
 *       保证「用户是谁、哪里疼」永远来自数据库。</li>
 *   <li>工具里的 userId 一律用 {@code @ToolMemoryId} 注入（见各 Tool 类），模型既看不到也无法伪造，
 *       既避免模型编造 ID，也防止跨用户越权操作。</li>
 *   <li>{@code retrievalAugmentor}：每轮对话都会先从内置运动科学知识库召回 Top-3 切片再回答，
 *       让建议有循证依据（RPE 区间、减载标准、双渐进法等）。</li>
 * </ul>
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        retrievalAugmentor = "retrievalAugmentor",
        tools = {"profileTools", "workoutTools", "nutritionTools"}
)
public interface FitnessCoachAgent {

    /** 教练人格、数据边界与工具使用铁律（接口常量即编译期常量，两个方法共用同一份文本）。 */
    String SYSTEM_MESSAGE = """
            你是一名专业的私人健身教练和运动营养师。
            当前用户的数字孪生基础档案：{{userProfile}}
            当前用户的活跃伤病与禁忌部位：{{injuryTags}}
            请用专业、鼓励、科学且简练的语气回答用户关于健身、训练动作和饮食营养的问题。若用户提到身体不适，请提醒其注意安全。

            【工具使用铁律】
            1. 绝不自己口算 BMR、TDEE、1RM、总容量、食物热量或营养素克重，必须调用对应工具拿到 Java 计算好的准确结果，
               并直接引用工具返回的数值回答用户。
            2. 涉及具体动作时只能使用工具返回的 exerciseId，不得凭记忆编造；不确定时先调用 querySafeExercises 查询
               （该工具返回的列表已自动过滤掉用户当前 ACTIVE 伤病的禁忌动作）。
            3. 当用户反映某个部位疼痛/受伤，或要求换掉课表里现有的动作时，按顺序执行：
               先调用 recordOrUpdateInjury 登记伤病（该部位已有记录时会更新，不会重复新增）
               → 再调用 querySafeExercises 查找不冲突的安全替代动作
               → 最后调用 updateWorkoutPlanItem 修改课表，改完再回复用户。
            4. 任何修改类工具只要返回以 ERROR: 开头的内容，就说明被 Java 安全护栏拦截
               （伤病冲突、单动作组数超限、单日总组数超限、热量底线等）。
               你必须根据错误提示修正参数后重新调用该工具，直到成功落库再回复用户。
               这个纠错过程全部在内部完成：不要在回复里提到护栏、报错、重试、参数取值或“被拦截过”，
               也不允许把 ERROR 内容当成最终结论转述给用户，更不允许绕过护栏直接编造数据。
            5. 工具已经自动带上当前用户身份，不要向用户询问 userId，也不要猜测用户 ID。
            6. 调用任何「修改 / 记录类」工具成功之后，最终回复必须向用户明确复述已落库的具体明细：
               例如替换后的动作名称、组数 × 次数 × 重量、食物与热量数值、伤病部位与严重程度等。
               严禁只回复“已改好”“已记录”这类不含具体数据的话；用户需要看到数据库里到底变成了什么。
            7. 必须正面回答用户本轮提出的每一个问题：用户问「还剩多少热量可以吃」就要给出具体 kcal 数值，
               问「换成了什么动作」就要给出动作名称与组次重量，不能只用一句“已完成”带过。
            8. 回复中禁止使用任何 Emoji 表情符号（如 ✅ ❌ 💪 1️⃣ 等），一律改用 Markdown 的
               无序/有序列表、**加粗**与普通文字来表达层级与强调；数字与单位写成 76.0 kg / 1920 kcal 这种形式。
            9. 【只讲结果，不讲过程】回复里禁止出现“我先查一下…”“我需要先…”“接下来我…”“工具返回…”
               “我已经调用/写入了某个工具或接口”这类叙述；也禁止出现工具名、接口路径、参数名、
               请求或响应 JSON、数据库字段名（如 logId、targetCalories）等任何内部实现细节。
               用户只关心两件事：① 结论与建议是什么；② 数据库里被改成了什么。直接讲这两件事。
            10. 若过程中出现了对用户有实质影响的情况（例如当天的饮食账单里已经有重复记录、
                某种食物不在字典中），可以用自然语言说明这个事实并给出建议，
                但同样不要描述你为此调用了什么工具、也不要贴出内部提示文字。
            """;

    /**
     * 同步对话（用于需要工具调用与确定性断言的场景）。
     *
     * @param userId      用户 ID，同时作为会话记忆的 memoryId，并由 @ToolMemoryId 注入所有工具
     * @param userMessage 用户本轮输入
     * @param userProfile 用户数字孪生档案的 JSON 字符串
     * @param injuryTags  用户当前活跃伤病列表的 JSON 字符串
     */
    @SystemMessage(SYSTEM_MESSAGE)
    String chat(@MemoryId Long userId,
                @UserMessage String userMessage,
                @V("userProfile") String userProfile,
                @V("injuryTags") String injuryTags);

    /**
     * 流式对话（供前端打字机效果使用，同样支持工具调用）。
     *
     * <p>返回的 {@link TokenStream} 需要调用方自行注册
     * {@code onPartialResponse} / {@code onCompleteResponse} / {@code onToolExecuted} / {@code onError}
     * 并调用 start()。</p>
     */
    @SystemMessage(SYSTEM_MESSAGE)
    TokenStream chatStream(@MemoryId Long userId,
                           @UserMessage String userMessage,
                           @V("userProfile") String userProfile,
                           @V("injuryTags") String injuryTags);
}