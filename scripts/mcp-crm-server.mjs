import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { readFileSync } from "node:fs";

// ---- Load CRM data ----
const crmPath = new URL("../data/crm.json", import.meta.url);
const crm = JSON.parse(readFileSync(crmPath, "utf-8"));

// ---- MCP Server ----
const server = new Server(
  { name: "crm-server", version: "1.0.0" },
  { capabilities: { tools: {} } }
);

// ---- Tools definition ----
const TOOLS = [
  {
    name: "get_ticket",
    description: "Получить тикет поддержки по ID",
    inputSchema: {
      type: "object",
      properties: {
        ticket_id: { type: "number", description: "ID тикета" },
      },
      required: ["ticket_id"],
    },
  },
  {
    name: "get_user",
    description: "Получить данные пользователя по ID",
    inputSchema: {
      type: "object",
      properties: {
        user_id: { type: "number", description: "ID пользователя" },
      },
      required: ["user_id"],
    },
  },
  {
    name: "list_user_tickets",
    description: "Получить все тикеты пользователя по его ID",
    inputSchema: {
      type: "object",
      properties: {
        user_id: { type: "number", description: "ID пользователя" },
      },
      required: ["user_id"],
    },
  },
  {
    name: "search_tickets",
    description: "Поиск тикетов по ключевому слову в теме или сообщениях",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Поисковый запрос" },
      },
      required: ["query"],
    },
  },
];

// ---- Handlers ----
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: TOOLS,
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  switch (name) {
    case "get_ticket": {
      const ticket = crm.tickets.find((t) => t.id === args.ticket_id);
      if (!ticket) {
        return {
          content: [
            { type: "text", text: `Тикет #${args.ticket_id} не найден` },
          ],
        };
      }
      const user = crm.users.find((u) => u.id === ticket.userId);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify({ ...ticket, userName: user?.name }, null, 2),
          },
        ],
      };
    }

    case "get_user": {
      const user = crm.users.find((u) => u.id === args.user_id);
      if (!user) {
        return {
          content: [
            {
              type: "text",
              text: `Пользователь #${args.user_id} не найден`,
            },
          ],
        };
      }
      const ticketCount = crm.tickets.filter(
        (t) => t.userId === user.id
      ).length;
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify({ ...user, ticketCount }, null, 2),
          },
        ],
      };
    }

    case "list_user_tickets": {
      const tickets = crm.tickets.filter((t) => t.userId === args.user_id);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(tickets, null, 2),
          },
        ],
      };
    }

    case "search_tickets": {
      const q = args.query.toLowerCase();
      const found = crm.tickets.filter(
        (t) =>
          t.subject.toLowerCase().includes(q) ||
          t.messages.some((m) => m.toLowerCase().includes(q))
      );
      return {
        content: [
          {
            type: "text",
            text:
              found.length > 0
                ? JSON.stringify(found, null, 2)
                : `Тикеты по запросу "${args.query}" не найдены`,
          },
        ],
      };
    }

    default:
      return {
        content: [{ type: "text", text: `Неизвестный инструмент: ${name}` }],
        isError: true,
      };
  }
});

// ---- Start ----
const transport = new StdioServerTransport();
await server.connect(transport);
