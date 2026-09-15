import { existsSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { Codex } from "@openai/codex-sdk";
import type {
  TranslationOptions,
  TranslationProvider,
  TranslationRequest,
  TranslationResult,
} from "../../src/domain/translator";
import { AppError } from "../errors";
import {
  buildTranslationPrompt,
  createPairOutputSchema,
  normalizeTranslationResult,
} from "./prompt";

export class CodexTranslationProvider implements TranslationProvider {
  readonly id = "codex" as const;
  private readonly isolatedWorkingDirectory = mkdtempSync(
    path.join(tmpdir(), "between-codex-"),
  );
  private readonly codexPathOverride?: string;

  constructor(
    cliPath?: string,
  ) {
    this.codexPathOverride = resolveCodexPath(cliPath);
  }

  async translate(
    request: TranslationRequest,
    options: TranslationOptions,
  ): Promise<TranslationResult> {
    const prompt = buildTranslationPrompt(request);
    const codex = new Codex({
      ...(this.codexPathOverride ? { codexPathOverride: this.codexPathOverride } : {}),
      config: { developer_instructions: prompt.systemInstruction },
    });
    const thread = codex.startThread({
      model: options.model,
      modelReasoningEffort: "low",
      workingDirectory: this.isolatedWorkingDirectory,
      skipGitRepoCheck: true,
      sandboxMode: "read-only",
      approvalPolicy: "never",
      networkAccessEnabled: false,
      webSearchMode: "disabled",
    });

    try {
      const turn = await thread.run(prompt.userInput, {
        outputSchema: createPairOutputSchema(request),
        signal: AbortSignal.timeout(90_000),
      });
      const parsed = JSON.parse(turn.finalResponse) as unknown;
      return normalizeTranslationResult(parsed, request);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new AppError(
        `Codex translation failed. Confirm that the local Codex session is signed in. ${message}`,
        502,
        { cause: error },
      );
    }
  }
}

function resolveCodexPath(configuredPath?: string): string | undefined {
  if (configuredPath) return configuredPath;

  const macAppCli = "/Applications/ChatGPT.app/Contents/Resources/codex";
  return existsSync(macAppCli) ? macAppCli : undefined;
}
