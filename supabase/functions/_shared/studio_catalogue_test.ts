import {parseCatalogue} from "./studio_catalogue.ts";
function assert(value:unknown){if(!value)throw new Error("Assertion failed");}
const renderer={musicResponsiveListItemRenderer:{playlistItemData:{videoId:"123456789ab"},flexColumns:[
  {musicResponsiveListItemFlexColumnRenderer:{text:{runs:[{text:"Real title",navigationEndpoint:{watchEndpoint:{videoId:"123456789ab"}}}]}}},
  {musicResponsiveListItemFlexColumnRenderer:{text:{runs:[{text:"Artist",navigationEndpoint:{browseEndpoint:{browseId:"UC123456789012"}}},{text:"Album",navigationEndpoint:{browseEndpoint:{browseId:"MPRE123456789012"}}}]}}}
]}};
Deno.test("catalogue IDs match Android, deduplicate, preserve identity and deny media editing",()=>{
  const root={contents:[renderer,renderer]};
  const tracks=parseCatalogue(root,"track");assert(tracks.length===1);assert(tracks[0].id==="yt:123456789ab");assert(tracks[0].artist==="Artist");assert(tracks[0].editable===false);
  assert(parseCatalogue(root,"album")[0].id==="MPRE123456789012");assert(parseCatalogue(root,"artist")[0].id==="UC123456789012");
});
Deno.test("empty and malformed catalogue rows do not fabricate results",()=>{
  assert(parseCatalogue({contents:[{musicResponsiveListItemRenderer:{flexColumns:[]}}]},"track").length===0);
});
