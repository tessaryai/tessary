// SPDX-License-Identifier: Apache-2.0
import { auth } from "../api/client";

/** If the server cannot be reached the visitor still leaves, for the root. */
export async function signOut(): Promise<void> {
  try {
    const { frontendUrl } = await auth.logout();
    window.location.assign(frontendUrl);
  } catch {
    window.location.assign("/");
  }
}
