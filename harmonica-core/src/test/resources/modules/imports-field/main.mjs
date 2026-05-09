import { config } from "#config";
import sum from "#utils/sum";
import { fmt } from "#utils/format";
export const result = {
  cfg: config.version,
  total: sum(20, 22),
  greeting: fmt("hi"),
};
