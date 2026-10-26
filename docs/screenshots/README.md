# 界面截图

把运行中的页面截图放进这个目录，然后回到仓库根目录的 `README.md`，把「界面截图」一节里的占位表格替换成图片即可（GitHub 会自动渲染）。

建议命名：

| 文件名 | 对应页面 | 路由 |
|---|---|---|
| `dashboard.png` | 数据看板 | `/` |
| `workout.png` | 周课表与打卡 | `/workout` |
| `nutrition.png` | 饮食与自定义套餐 | `/nutrition` |
| `twin.png` | 数字孪生与伤病矩阵 | `/twin` |
| `observability.png` | 可观测性与 RAG 召回调试 | `/observability` |

替换成下面这种写法即可：

```markdown
| 数据看板 | 周课表与打卡 |
|---|---|
| ![数据看板](docs/screenshots/dashboard.png) | ![周课表](docs/screenshots/workout.png) |
```

**截图小建议**（这个项目的设计系统是深色 + 酸柠檬高亮，截图效果很好）：

1. 先把演示数据播满（数据看板右上角「一键播种演示数据」），图表才有内容；
2. 窗口宽度 ≥ 1440px，浏览器缩放 100%，避免 Bento 栅格换行；
3. 用浏览器自带的截图（DevTools → `Ctrl+Shift+P` → `Capture full size screenshot`）可一次拍全整页；
4. 顺手拍一张 AI 教练对话（Copilot 浮舱），能体现"只讲结果不讲过程"的流式效果。