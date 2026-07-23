#!/bin/bash
# Funny English server names (Adjective + Place) — used when SERVER_NAME / MOTD unset.
set -euo pipefail

ADJECTIVES=(
  Rainbow Thorny Carefree Misty Whispering Cobalt Amber Velvet Rusty Golden
  Silver Cloudy Stormy Sleepy Peppy Drowsy Spicy Tangy Zesty Quirky Wobbly
  Bouncy Fuzzy Mossy Sandy Dusty Frosty Sunny Moonlit Starlit Hidden Secret
  Lost Found Wandering Drifting Floating Sparkling Twinkling Glowing Shimmering
  Crooked Twisted Tangled Tangy Peppery Honeyed Maple Copper Brass Jade Ivory
  Crimson Azure Scarlet Violet Indigo Emerald Sapphire Pearl Coral Lagoon
  Quiet Loud Gentle Wild Free Bold Brave Cheeky Sassy Snappy Nifty Dandy
  Cozy Snug Soft Warm Cool Crisp Fresh Breezy Airy Leafy Flowery Meadowy
  Pepper Pickle Pumpkin Butter Cookie Candy Cocoa Cinnamon Ginger Lemon Lime
)

PLACES=(
  Forest Valley Creek Meadow Grove Hollow Ridge Canyon Bluff Cove Bay
  Harbor Pier Shore Beach Dune Marsh Bog Fen Glade Clearing Thicket
  Orchard Garden Pasture Prairie Plateau Summit Peak Cliff Cascade Falls
  Rapids Brook Stream River Lake Pond Spring Well Oasis Lagoon Inlet
  Bridge Mill Cabin Lodge Camp Trail Path Road Lane Alley Square Plaza
  Keep Tower Spire Gate Arch Nest Den Lair Burrow Warren Nest Haven
  Refuge Sanctuary Retreat Hideout Outlook Vista Lookout Horizon Frontier
  Isle Island Reef Atoll Delta Basin Gorge Ravine Pass Notch Gap
)

pick() {
  local -n arr=$1
  echo "${arr[RANDOM % ${#arr[@]}]}"
}

echo "$(pick ADJECTIVES) $(pick PLACES)"
