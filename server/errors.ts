export class AppError extends Error {
  constructor(
    message: string,
    readonly statusCode = 500,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "AppError";
  }
}

export function toAppError(error: unknown): AppError {
  if (error instanceof AppError) return error;
  if (error instanceof Error) return new AppError(error.message, 500, { cause: error });
  return new AppError("Unexpected server error.");
}
