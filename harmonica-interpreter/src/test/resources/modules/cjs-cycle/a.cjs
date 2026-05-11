exports.aDone = false;
const b = require("./b.cjs");
exports.fromB = b.bMid;     // value of b at the time we resume
exports.aDone = true;
