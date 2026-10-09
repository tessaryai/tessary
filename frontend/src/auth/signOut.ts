// SPDX-License-Identifier: Apache-2.0
import { auth } from "../api/client";

/**
 * Ends the session and leaves for wherever the server says a signed-out visitor lands. If the server
 * cannot be reached the visitor still leaves, for the root, which sends them to sign in once the
 * session is found gone.
 */
export async function signOut(): Promise<void> {
  try {
    const { frontendUrl } = await auth.logout();
    window.location.assign(frontendUrl);
  } catch {
    window.location.assign("/");
  }
}
