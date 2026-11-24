{
  inputs = {
    # Mill 1.x is not in typelevel-nix's pinned nixpkgs (it has 0.12.x),
    # so take mill from nixos-unstable, like TorrentDam/bittorrent does.
    nixpkgs-unstable.url = "github:NixOS/nixpkgs/nixos-unstable";
    typelevel-nix.url = "github:typelevel/typelevel-nix";
    nixpkgs.follows = "typelevel-nix/nixpkgs";
    flake-utils.follows = "typelevel-nix/flake-utils";
  };

  outputs = { self, nixpkgs, nixpkgs-unstable, flake-utils, typelevel-nix }:
    # nixos-unstable (26.11) dropped x86_64-darwin, so only list supported systems
    flake-utils.lib.eachSystem [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ] (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ typelevel-nix.overlays.default ];
        };
        mill = nixpkgs-unstable.legacyPackages.${system}.mill;
      in {
        devShells.default = pkgs.devshell.mkShell {
          imports = [ typelevel-nix.typelevelShell ];
          name = "torrentdam-server-shell";
          packages = [ mill ];
          typelevelShell = {
            jdk.package = pkgs.jdk25;
            native.enable = true;
            nodejs.enable = true;
          };
        };
      });
}
