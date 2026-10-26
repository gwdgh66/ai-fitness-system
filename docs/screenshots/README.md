# 界面截图

本目录用于存放项目界面截图。建议命名：

| 文件名 | 对应页面 | 路由 |
|---|---|---|
| `dashboard.png` | 数据看板 | `/` |
| `workout.png` | 周课表与打卡 | `/workout` |
| `nutrition.png` | 饮食与自定义套餐 | `/nutrition` |
| `twin.png` | 数字孪生与伤病矩阵 | `/twin` |
| `observability.png` | 可观测性与 RAG 召回调试 | `/observability` |

在仓库根目录 `README.md` 的「界面截图」一节中按下表引用即可（GitHub 会自动渲染）：

```markdown
| 数据看板 | 周课表与打卡 |
|---|---|
| ![数据看板](docs/screenshots/dashboard.png) | ![周课表](docs/screenshots/workout.png) |
```

## 截图建议

1. **先播满演示数据**（数据看板右上角「一键播种演示数据」），图表与指标卡才有内容；
2. 窗口宽度 ≥ 1440px、浏览器缩放 100%，避免 Bento 栅格换行；
3. 用浏览器开发者工具截整页：`Ctrl+Shift+P` → `Capture full size screenshot`；
4. 另外建议补一张 **AI 教练对话**截图（右下角 Copilot 浮舱），可体现 SSE 流式与「只讲结果、不讲过程」的效果。

> 设计系统为 **Kinetic Dark & Acid Lime**（深空底色 + 酸柠檬高亮 + Bento 栅格 + 等宽数字读数），深色主题下截图观感较好。