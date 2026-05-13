package ee.authplayground.idpserver.features.users.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the custom Thymeleaf login page at {@code /login}.
 * <p>
 * The page presents a method picker (username/password vs. Smart-ID) and
 * the form fields for the selected method. The Smart-ID panel is a
 * placeholder for the v1 release — its fields are visible but its submit
 * button is disabled and labelled "Coming soon."
 * <p>
 * Spring Security's form-login filter posts to {@code POST /login}
 * automatically; on auth failure it redirects to
 * {@code /login?error}, which we surface as a flash banner in the template.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout,
            Model model
    ) {
        if (error != null) {
            model.addAttribute("error", "Invalid username or password.");
        }
        if (logout != null) {
            model.addAttribute("message", "You have been signed out.");
        }
        return "login";
    }
}
