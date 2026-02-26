import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { readFileSync, writeFileSync } from "node:fs";

// ---- Load project data ----
const dataPath = new URL("../data/tasks.json", import.meta.url);

function loadData() {
  return JSON.parse(readFileSync(dataPath, "utf-8"));
}

function saveData(data) {
  writeFileSync(dataPath, JSON.stringify(data, null, 2), "utf-8");
}

// ---- MCP Server ----
const server = new Server(
  { name: "pm-server", version: "1.0.0" },
  { capabilities: { tools: {} } }
);

// ---- Tools definition ----
const TOOLS = [
  {
    name: "list_tasks",
    description:
      "Список задач проекта с опциональной фильтрацией по статусу, приоритету или исполнителю",
    inputSchema: {
      type: "object",
      properties: {
        status: {
          type: "string",
          description:
            "Фильтр по статусу: todo, in_progress, review, done",
        },
        priority: {
          type: "string",
          description: "Фильтр по приоритету: low, medium, high, critical",
        },
        assignee_id: {
          type: "number",
          description: "Фильтр по ID исполнителя",
        },
      },
    },
  },
  {
    name: "get_task",
    description: "Получить детальную информацию о задаче по ID",
    inputSchema: {
      type: "object",
      properties: {
        task_id: { type: "number", description: "ID задачи" },
      },
      required: ["task_id"],
    },
  },
  {
    name: "create_task",
    description: "Создать новую задачу в проекте",
    inputSchema: {
      type: "object",
      properties: {
        title: { type: "string", description: "Название задачи" },
        description: { type: "string", description: "Описание задачи" },
        priority: {
          type: "string",
          description: "Приоритет: low, medium, high, critical",
        },
        assignee_id: { type: "number", description: "ID исполнителя" },
        deadline: { type: "string", description: "Дедлайн (YYYY-MM-DD)" },
        tags: {
          type: "array",
          items: { type: "string" },
          description: "Теги задачи",
        },
      },
      required: ["title", "description", "priority", "assignee_id"],
    },
  },
  {
    name: "update_task",
    description: "Обновить статус, приоритет или исполнителя задачи",
    inputSchema: {
      type: "object",
      properties: {
        task_id: { type: "number", description: "ID задачи" },
        status: {
          type: "string",
          description: "Новый статус: todo, in_progress, review, done",
        },
        priority: {
          type: "string",
          description: "Новый приоритет: low, medium, high, critical",
        },
        assignee_id: {
          type: "number",
          description: "Новый ID исполнителя",
        },
      },
      required: ["task_id"],
    },
  },
  {
    name: "get_project_summary",
    description:
      "Сводка по проекту: спринт, прогресс, статистика по статусам и приоритетам",
    inputSchema: {
      type: "object",
      properties: {},
    },
  },
  {
    name: "get_member",
    description:
      "Информация о члене команды по ID и список его задач",
    inputSchema: {
      type: "object",
      properties: {
        member_id: { type: "number", description: "ID члена команды" },
      },
      required: ["member_id"],
    },
  },
];

// ---- Handlers ----
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: TOOLS,
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;
  const data = loadData();

  switch (name) {
    case "list_tasks": {
      let tasks = data.tasks;
      if (args.status) {
        tasks = tasks.filter((t) => t.status === args.status);
      }
      if (args.priority) {
        tasks = tasks.filter((t) => t.priority === args.priority);
      }
      if (args.assignee_id) {
        tasks = tasks.filter((t) => t.assigneeId === args.assignee_id);
      }

      const result = tasks.map((t) => {
        const member = data.members.find((m) => m.id === t.assigneeId);
        return {
          id: t.id,
          title: t.title,
          status: t.status,
          priority: t.priority,
          assignee: member ? member.name : "не назначен",
          deadline: t.deadline,
          tags: t.tags,
        };
      });

      return {
        content: [
          {
            type: "text",
            text:
              result.length > 0
                ? JSON.stringify(result, null, 2)
                : "Задачи не найдены",
          },
        ],
      };
    }

    case "get_task": {
      const task = data.tasks.find((t) => t.id === args.task_id);
      if (!task) {
        return {
          content: [
            { type: "text", text: `Задача #${args.task_id} не найдена` },
          ],
        };
      }
      const member = data.members.find((m) => m.id === task.assigneeId);
      const blockedByTasks = task.blockedBy.map((bid) => {
        const bt = data.tasks.find((t) => t.id === bid);
        return bt
          ? { id: bt.id, title: bt.title, status: bt.status }
          : { id: bid, title: "неизвестна", status: "unknown" };
      });
      const blocking = data.tasks
        .filter((t) => t.blockedBy.includes(task.id))
        .map((t) => ({ id: t.id, title: t.title, status: t.status }));

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                ...task,
                assigneeName: member ? member.name : "не назначен",
                assigneeRole: member ? member.role : null,
                blockedByDetails: blockedByTasks,
                blocking: blocking,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    case "create_task": {
      const maxId = data.tasks.reduce(
        (max, t) => Math.max(max, t.id),
        0
      );
      const newTask = {
        id: maxId + 1,
        title: args.title,
        description: args.description,
        status: "todo",
        priority: args.priority,
        assigneeId: args.assignee_id,
        createdAt: new Date().toISOString().split("T")[0],
        deadline: args.deadline || null,
        tags: args.tags || [],
        blockedBy: [],
      };
      data.tasks.push(newTask);
      saveData(data);

      const member = data.members.find(
        (m) => m.id === args.assignee_id
      );
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                ...newTask,
                assigneeName: member ? member.name : "не назначен",
                message: `Задача #${newTask.id} создана`,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    case "update_task": {
      const taskIndex = data.tasks.findIndex(
        (t) => t.id === args.task_id
      );
      if (taskIndex === -1) {
        return {
          content: [
            {
              type: "text",
              text: `Задача #${args.task_id} не найдена`,
            },
          ],
        };
      }

      const task = data.tasks[taskIndex];
      const changes = [];
      if (args.status) {
        changes.push(`статус: ${task.status} → ${args.status}`);
        task.status = args.status;
      }
      if (args.priority) {
        changes.push(`приоритет: ${task.priority} → ${args.priority}`);
        task.priority = args.priority;
      }
      if (args.assignee_id) {
        const oldMember = data.members.find(
          (m) => m.id === task.assigneeId
        );
        const newMember = data.members.find(
          (m) => m.id === args.assignee_id
        );
        changes.push(
          `исполнитель: ${oldMember?.name || task.assigneeId} → ${newMember?.name || args.assignee_id}`
        );
        task.assigneeId = args.assignee_id;
      }

      data.tasks[taskIndex] = task;
      saveData(data);

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                id: task.id,
                title: task.title,
                changes: changes,
                message: `Задача #${task.id} обновлена`,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    case "get_project_summary": {
      const { project, tasks, members } = data;
      const statusCounts = {};
      const priorityCounts = {};
      for (const t of tasks) {
        statusCounts[t.status] = (statusCounts[t.status] || 0) + 1;
        priorityCounts[t.priority] =
          (priorityCounts[t.priority] || 0) + 1;
      }

      const today = new Date().toISOString().split("T")[0];
      const overdue = tasks.filter(
        (t) => t.deadline < today && t.status !== "done"
      );
      const blocked = tasks.filter(
        (t) =>
          t.blockedBy.length > 0 &&
          t.blockedBy.some((bid) => {
            const bt = tasks.find((x) => x.id === bid);
            return bt && bt.status !== "done";
          })
      );

      const memberLoad = members.map((m) => ({
        name: m.name,
        role: m.role,
        activeTasks: tasks.filter(
          (t) =>
            t.assigneeId === m.id &&
            (t.status === "in_progress" || t.status === "todo")
        ).length,
      }));

      const progress = tasks.length > 0
        ? Math.round(
            (tasks.filter((t) => t.status === "done").length /
              tasks.length) *
              100
          )
        : 0;

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                project: project,
                progress: `${progress}%`,
                totalTasks: tasks.length,
                byStatus: statusCounts,
                byPriority: priorityCounts,
                overdueTasks: overdue.map((t) => ({
                  id: t.id,
                  title: t.title,
                  deadline: t.deadline,
                })),
                blockedTasks: blocked.map((t) => ({
                  id: t.id,
                  title: t.title,
                  blockedBy: t.blockedBy,
                })),
                teamLoad: memberLoad,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    case "get_member": {
      const member = data.members.find((m) => m.id === args.member_id);
      if (!member) {
        return {
          content: [
            {
              type: "text",
              text: `Член команды #${args.member_id} не найден`,
            },
          ],
        };
      }

      const memberTasks = data.tasks
        .filter((t) => t.assigneeId === member.id)
        .map((t) => ({
          id: t.id,
          title: t.title,
          status: t.status,
          priority: t.priority,
          deadline: t.deadline,
        }));

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                ...member,
                tasks: memberTasks,
                activeTasks: memberTasks.filter(
                  (t) =>
                    t.status === "in_progress" || t.status === "todo"
                ).length,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    default:
      return {
        content: [
          { type: "text", text: `Неизвестный инструмент: ${name}` },
        ],
        isError: true,
      };
  }
});

// ---- Start ----
const transport = new StdioServerTransport();
await server.connect(transport);
