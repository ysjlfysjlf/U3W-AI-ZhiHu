## 📌 Description

基于 Java 17 + Spring Boot + RuoYi + Playwright + Redis + WebSocket + Nginx + Vue + 微信小程序的多 AI 集成与自动化执行项目。

## 🧩 功能模块

- AI 主机核心服务端
- 控制台后端
- 控制台前端
- 控制台微信小程序端

## ⚙️ 环境部署要求

- JDK 17
- Maven 3.9+
- MySQL 5.7+
- Redis 6.0+
- Node.js 16.x
- Git

<img width="865" height="390" alt="image" src="https://github.com/user-attachments/assets/492db0a2-2e7e-4885-b7e7-3024e82d72f5" />

<img width="792" height="95" alt="image" src="https://github.com/user-attachments/assets/b70d8b70-160d-4bba-993f-5e57aec11d2e" />

> 修改 Redis 端口为 `26379`。

<img width="865" height="101" alt="image" src="https://github.com/user-attachments/assets/535d33ad-b082-4f56-88c1-9fe5063f5830" />

## 🖥 效果展示

### Web 端

**首页**：统一管理多 AI 登录状态，检查用户登录状态，显示用户名。

<img width="1535" height="484" alt="image" src="https://github.com/user-attachments/assets/034a7be0-a79b-4dd0-8c7d-fe68fb6c8c08" />

**主机页面**：包含 AI 选择配置、提示词输入、任务流程、执行过程截屏、执行结果、查看原链接等功能。

![image](https://github.com/ysjlfysjlf/U3W-AI-ZhiHu/blob/27500c6f119024dc436bba03a1e8c46e2eb4952d/host.gif)

**AI 选择配置**：可以开启或关闭 AI、深度思考模式、联网模式。提示词输入模块中，输入提示词后，AI 主机核心服务端会把提示词分发给所有 AI。

<img width="1220" height="343" alt="image" src="https://github.com/user-attachments/assets/63ea1e8f-3b3d-49ac-bc17-27810f103cfa" />

**任务流程**：显示任务执行的流程；主机可视化模块显示任务执行过程中的截屏。

<img width="1273" height="641" alt="image" src="https://github.com/user-attachments/assets/b1c3bfe2-b6fa-489f-a1fc-2ec2c3d6d360" />

**执行结果**：展示最终的执行结果，可以查看原链接、导出 MD 文件、智能评分、投递到公众号等。

<img width="1267" height="804" alt="image" src="https://github.com/user-attachments/assets/dc4336cb-563d-40dd-a9f4-d0e501e22371" />

### 小程序端

**首页**：展示已有 AI。

<img width="301" height="646" alt="image" src="https://github.com/user-attachments/assets/189c69da-b5a5-4310-acd5-296801c76bc0" />

**控制台**：包含 AI 选择配置、提示词输入、AI 执行状态、执行结果等功能模块。

![控制台](https://github.com/user-attachments/assets/5d52e374-9885-4c52-9b10-8809ede08382)
