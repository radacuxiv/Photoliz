import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.overrideUserInterfaceStyle = .dark
        window.backgroundColor = .black
        window.rootViewController = CameraViewController()
        window.makeKeyAndVisible()
        self.window = window
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        (window?.rootViewController as? CameraViewController)?.appBecameActive()
    }

    func sceneWillResignActive(_ scene: UIScene) {
        (window?.rootViewController as? CameraViewController)?.appWillResignActive()
    }
}
