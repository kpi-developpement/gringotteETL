FROM node:18-alpine AS base

# Étape 1 : Dépendances
FROM base AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install

# Étape 2 : Build
FROM base AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# HADA HOUWA STAR LI ZEDNA BACH Y-MSE7 L'FICHIER L'MKHEREBE9
RUN rm -f next.config.ts 
RUN npm run build

# Étape 3 : Production
FROM base AS runner
WORKDIR /app
ENV NODE_ENV production
ENV NEXT_TELEMETRY_DISABLED 1

RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs

EXPOSE 3112
ENV PORT 3112
ENV HOSTNAME "0.0.0.0"

CMD ["node", "server.js"]