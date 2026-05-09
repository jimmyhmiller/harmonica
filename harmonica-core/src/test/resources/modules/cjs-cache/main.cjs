const r1 = require("./lib.cjs");
const r2 = require("./lib.cjs");
module.exports = { same: r1 === r2, stamp: r1.stamp };
