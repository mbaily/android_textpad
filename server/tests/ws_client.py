from simple_websocket import Client


class ClosingClient(Client):
    """The library leaves the socket open when an upgrade is rejected."""
    def handshake(self):
        try:
            super().handshake()
        except BaseException:
            self.sock.close()
            raise
