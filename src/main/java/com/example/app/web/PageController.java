package com.example.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the HTML shells. Every page is public; the page's JavaScript checks for a token
 * and calls the JSON API, redirecting to /login when it needs a user.
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String register() {
        return "register";
    }

    @GetMapping("/polls/new")
    public String newPoll(Model model) {
        model.addAttribute("pollId", null);
        model.addAttribute("pageTitle", "New poll");
        return "poll-form";
    }

    @GetMapping("/polls/{id}/edit")
    public String editPoll(@PathVariable Long id, Model model) {
        model.addAttribute("pollId", id);
        model.addAttribute("pageTitle", "Edit poll");
        return "poll-form";
    }

    @GetMapping("/p/{shareId}")
    public String viewPoll(@PathVariable String shareId, Model model) {
        model.addAttribute("shareId", shareId);
        return "poll-view";
    }
}
