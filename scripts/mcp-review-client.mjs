import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

// ---- Configuration from environment ----
const {
  GITHUB_TOKEN,
  DEEPSEEK_API_KEY,
  REPO_OWNER,
  REPO_NAME,
  PR_NUMBER,
  GITHUB_STEP_SUMMARY,
} = process.env;

if (!GITHUB_TOKEN) throw new Error("GITHUB_TOKEN is required");
if (!DEEPSEEK_API_KEY) throw new Error("DEEPSEEK_API_KEY is required");
if (!PR_NUMBER) throw new Error("PR_NUMBER is required");

const owner = REPO_OWNER;
const repo = REPO_NAME;
const prNumber = Number(PR_NUMBER);

// ---- 1. Connect to GitHub MCP server via stdio ----
console.log("Starting GitHub MCP server...");

const transport = new StdioClientTransport({
  command: "npx",
  args: ["-y", "@modelcontextprotocol/server-github"],
  env: {
    ...process.env,
    GITHUB_PERSONAL_ACCESS_TOKEN: GITHUB_TOKEN,
  },
});

const client = new Client({ name: "rag-review-client", version: "1.0.0" });
await client.connect(transport);
console.log("Connected to GitHub MCP server");

// ---- 2. List available tools (debug) ----
const { tools } = await client.listTools();
const toolNames = tools.map((t) => t.name);
console.log(`\nAvailable MCP tools (${toolNames.length}):`);
for (const name of toolNames) {
  console.log(`  - ${name}`);
}

// ---- Helper: call tool with fallback names ----
async function callTool(candidates, args) {
  for (const name of candidates) {
    if (toolNames.includes(name)) {
      console.log(`\nCalling tool: ${name}`);
      const result = await client.callTool({ name, arguments: args });
      return result;
    }
  }
  throw new Error(
    `None of the tool candidates found: ${candidates.join(", ")}`
  );
}

// ---- 3. Fetch PR data via MCP ----
console.log("\n--- Fetching PR files (includes patches) ---");
const filesResult = await callTool(
  ["get_pull_request_files", "list_pull_request_files", "listPullRequestFiles"],
  { owner, repo, pull_number: prNumber }
);
const filesText = extractText(filesResult);
console.log(`PR files response length: ${filesText.length} chars`);

// The files response contains patches per file — use it as the diff
const diff = filesText;

console.log("\n--- Fetching docs ---");
const docFiles = ["docs/code-style.md", "docs/architecture.md"];
let allDocs = "";
for (const filePath of docFiles) {
  try {
    const docResult = await callTool(
      ["get_file_contents", "getFileContents", "read_file"],
      { owner, repo, path: filePath }
    );
    const content = extractText(docResult);
    allDocs += `\n--- ${filePath} ---\n${content}\n`;
    console.log(`  Loaded ${filePath} (${content.length} chars)`);
  } catch (err) {
    console.warn(`  Warning: could not load ${filePath}: ${err.message}`);
  }
}

// ---- 4. RAG: select relevant doc chunks ----
console.log("\n--- RAG: selecting relevant context ---");
const chunks = splitByHeadings(allDocs);
const keywords = extractKeywords(diff);
console.log(`Keywords from diff: ${keywords.slice(0, 20).join(", ")}...`);

const scored = chunks
  .map((chunk) => ({
    chunk,
    score: scoreChunk(chunk, keywords),
  }))
  .sort((a, b) => b.score - a.score);

let relevantContext = "";
const maxContextLen = 5000;

for (const { chunk, score } of scored) {
  if (score === 0) continue;
  if (relevantContext.length + chunk.length > maxContextLen) break;
  relevantContext += chunk + "\n\n";
}

// Fallback: if nothing matched, use everything (trimmed)
if (!relevantContext.trim()) {
  console.log("No keyword matches — using all docs as context");
  relevantContext = allDocs.slice(0, maxContextLen);
} else {
  console.log(
    `Selected ${relevantContext.length} chars of relevant context from ${scored.filter((s) => s.score > 0).length} chunks`
  );
}

// ---- 5. Call DeepSeek API ----
console.log("\n--- Calling DeepSeek API ---");

const systemPrompt = `Ты — ревьюер проекта RagKotlin. Вот релевантные правила стиля и архитектура проекта:

${relevantContext}

Проанализируй diff и выдай: найденные проблемы, потенциальные баги, нарушения стиля, советы по улучшению. Отвечай на русском языке в формате Markdown.`;

const userPrompt = `Вот diff pull request для ревью:\n\n\`\`\`diff\n${diff.slice(0, 15000)}\n\`\`\``;

const response = await fetch(
  "https://api.deepseek.com/v1/chat/completions",
  {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${DEEPSEEK_API_KEY}`,
    },
    body: JSON.stringify({
      model: "deepseek-chat",
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: userPrompt },
      ],
      temperature: 0.3,
    }),
  }
);

const data = await response.json();
const review = data.choices?.[0]?.message?.content;

if (!review) {
  console.error("DeepSeek API returned no review content");
  console.error("Raw response:", JSON.stringify(data));
  process.exit(1);
}

// ---- 6. Output review ----
console.log("\n## AI Code Review\n");
console.log(review);

// Write to GitHub Step Summary
if (GITHUB_STEP_SUMMARY) {
  const { appendFileSync } = await import("node:fs");
  appendFileSync(
    GITHUB_STEP_SUMMARY,
    `## AI Code Review (MCP + RAG)\n\n${review}\n`
  );
  console.log("\nReview written to Step Summary");
}

// Disconnect MCP
await client.close();

// ---- Utility functions ----

function extractText(toolResult) {
  if (!toolResult?.content) return "";
  return toolResult.content
    .filter((c) => c.type === "text")
    .map((c) => c.text)
    .join("\n");
}

function splitByHeadings(text) {
  const parts = text.split(/(?=^## )/m);
  return parts.map((p) => p.trim()).filter((p) => p.length > 0);
}

function extractKeywords(diffText) {
  const words = new Set();

  // File names from diff headers
  const fileMatches = diffText.matchAll(/^(?:---|\+\+\+) [ab]\/(.+)$/gm);
  for (const m of fileMatches) {
    const parts = m[1].split("/");
    for (const part of parts) {
      const name = part.replace(/\.\w+$/, "");
      if (name.length > 2) words.add(name.toLowerCase());
    }
  }

  // Class / function / val / var names from Kotlin code
  const codePatterns = [
    /\b(?:class|object|interface)\s+(\w+)/g,
    /\b(?:fun)\s+(\w+)/g,
    /\b(?:val|var)\s+(\w+)/g,
    /\b(?:import)\s+[\w.]+\.(\w+)/g,
  ];
  for (const pattern of codePatterns) {
    for (const m of diffText.matchAll(pattern)) {
      if (m[1].length > 2) words.add(m[1].toLowerCase());
    }
  }

  return [...words];
}

function scoreChunk(chunk, keywords) {
  const lower = chunk.toLowerCase();
  let score = 0;
  for (const kw of keywords) {
    if (lower.includes(kw)) score++;
  }
  return score;
}
