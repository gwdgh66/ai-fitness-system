# Project Context: Personal AI Fitness System (Java + LangChain4j)

## Tech Stack
- JDK: Java 21 (Enable Virtual Threads, use `record` for DTOs/VOs)
- Framework: Spring Boot 3.3+
- ORM: MyBatis-Plus 3.5+ (Use `LambdaQueryWrapper`, avoid XML unless multi-table joins)
- Storage: MySQL 8.0 (business data), Redis (ChatMemory & cache)
- AI Framework: LangChain4j 1.22.0+ (Strictly use 1.x APIs under `dev.langchain4j.*`)

## Strict Rules (DO NOT VIOLATE)
1. **NO Math in LLM**: DO NOT let the LLM calculate BMI, BMR, TDEE, 1RM, or macro totals directly. Always write deterministic Java methods in the Service layer and expose them via `@Tool`.
2. **NO Legacy LangChain4j APIs**: DO NOT use deprecated 0.x classes. Use `@AiService`, `@Tool`, `@P`, `@MemoryId`, `@SystemMessage`, and `@UserMessage`.
3. **Tool Annotations**: Every `@Tool` method MUST have a clear description of WHEN to call it, and every parameter MUST carry `@P("description")`.
4. **Hard Guardrails**: Any `@Tool` performing INSERT/UPDATE on workout plans or diet logs MUST validate safe boundaries in Java code first (e.g., daily sets between 1 and 30, daily calories >= 1000 kcal).
5. **Step-by-Step Only**: DO NOT generate unrequested modules or layers in advance. Only create or modify the specific files requested in the current prompt.