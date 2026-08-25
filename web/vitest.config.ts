import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node", // core engine is DOM-free and runs in plain node
    include: ["test/**/*.test.ts"],
  },
});
