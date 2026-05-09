const root    = require("utilkit");
const strings = require("utilkit/strings");
const fpMap   = require("utilkit/fp/map");
module.exports = {
  rootHello: root.hello,
  upper: strings.upper("hi"),
  doubled: fpMap(function(x){ return x*2; })([1,2,3]),
};
