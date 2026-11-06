FROM node:22-bookworm-slim
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev --omit=optional --ignore-scripts
COPY broker.mjs server.mjs ./
USER node
CMD ["node", "server.mjs"]
