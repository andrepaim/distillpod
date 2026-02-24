import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/podcasts": "http://localhost:8124", "/player": "http://localhost:8124", "/snips": "http://localhost:8124", "/health": "http://localhost:8124" } },
});
